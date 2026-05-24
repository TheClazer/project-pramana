"""End-to-end verify — EXIF parse → manifest → JCS → ECDSA verify →
recompute pixel hash → compare. If EXIF is absent, scan DCT watermark.

Mirrors Kotlin `verify/RealVerifyEngine.kt`.
"""
from __future__ import annotations

import base64
import hashlib
import io
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

import numpy as np
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ec import EllipticCurvePublicKey
from PIL import Image

from .dct_watermark import crop_to_8, extract, rgb_to_ycbcr
from .exif_io import read_user_comment
from .jcs_compat import canonicalize_obj
from .keystore import verify as ec_verify
from .manifest import Manifest


@dataclass
class VerifyResult:
    state: str          # "verified_original" | "verified_but_modified" | "broken_seal" | "no_provenance" | "unreadable"
    manifest: Optional[Manifest] = None
    reason: str = ""
    watermark_found: bool = False


def _load_pubkey_from_b64(b64: str) -> EllipticCurvePublicKey:
    der = base64.b64decode(b64)
    return serialization.load_der_public_key(der)


def verify_image(
    image_path: Path,
    trust_pubkeys_b64: dict[str, str],
) -> VerifyResult:
    """`trust_pubkeys_b64` maps keyId hex → base64 SubjectPublicKeyInfo DER."""
    raw = image_path.read_bytes()

    # EXIF path
    manifest_bytes = read_user_comment(image_path)
    if manifest_bytes is not None:
        return _verify_with_manifest(raw, manifest_bytes, trust_pubkeys_b64)

    # DCT path — read pixel watermark if no EXIF
    img = Image.open(io.BytesIO(raw)).convert("RGB")
    rgb = crop_to_8(np.asarray(img))
    y, _, _ = rgb_to_ycbcr(rgb)
    kind, _payload = extract(y)
    if kind in ("found", "damaged"):
        return VerifyResult(state="broken_seal", reason="watermark_orphan", watermark_found=True)

    return VerifyResult(state="no_provenance", reason="no_manifest_no_watermark")


def _verify_with_manifest(
    image_bytes: bytes,
    manifest_bytes: bytes,
    trust_pubkeys_b64: dict[str, str],
) -> VerifyResult:
    try:
        manifest_dict = json.loads(manifest_bytes.decode("utf-8"))
        manifest = Manifest.from_dict(manifest_dict)
    except Exception as e:
        return VerifyResult(state="broken_seal", reason=f"manifest_malformed: {e}")

    pub_b64 = trust_pubkeys_b64.get(manifest.key_id)
    if pub_b64 is None:
        return VerifyResult(state="broken_seal", reason="unknown_key", manifest=manifest)

    try:
        pub = _load_pubkey_from_b64(pub_b64)
    except Exception as e:
        return VerifyResult(state="broken_seal", reason=f"key_load_failed: {e}", manifest=manifest)

    canon = canonicalize_obj(manifest.to_json_dict_for_signing())
    try:
        sig = base64.b64decode(manifest.signature)
    except Exception:
        return VerifyResult(state="broken_seal", reason="signature_invalid", manifest=manifest)

    if not ec_verify(pub, canon, sig):
        return VerifyResult(state="broken_seal", reason="signature_invalid", manifest=manifest)

    # Recompute pixel hash from canonical decoded pixels (8-aligned crop)
    img = Image.open(io.BytesIO(image_bytes)).convert("RGB")
    rgb = crop_to_8(np.asarray(img))
    actual_hash = hashlib.sha256(rgb.tobytes()).hexdigest()
    if actual_hash == manifest.content_hash:
        return VerifyResult(state="verified_original", manifest=manifest)
    return VerifyResult(state="verified_but_modified", manifest=manifest, reason=f"expected={manifest.content_hash[:16]}… actual={actual_hash[:16]}…")
