"""End-to-end seal — pixels → DCT watermark → JPEG → contentHash → manifest
→ JCS → ECDSA sign → embed EXIF.

Mirrors Kotlin `seal/RealSealEngine.kt` step-for-step. Output JPEG verifies
on Android (and vice versa) provided the public key is in the trust store.
"""
from __future__ import annotations

import base64
import hashlib
import io
import json
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

import numpy as np
from PIL import Image

from .dct_watermark import build_payload, crop_to_8, embed, rgb_to_ycbcr, ycbcr_to_rgb
from .exif_io import embed_user_comment
from .jcs_compat import canonicalize_obj
from .keystore import KeyPair, sign
from .manifest import DetectionMeta, Manifest, SensorMeta


@dataclass
class SealedFile:
    bytes_: bytes
    manifest: Manifest


def _sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _rgb_to_jpeg_bytes(rgb: np.ndarray, quality: int = 92) -> bytes:
    img = Image.fromarray(rgb, mode="RGB")
    out = io.BytesIO()
    img.save(out, format="JPEG", quality=quality)
    return out.getvalue()


def _jpeg_to_rgb(jpeg: bytes) -> np.ndarray:
    img = Image.open(io.BytesIO(jpeg)).convert("RGB")
    return np.asarray(img)


def seal_image(
    input_path: Path,
    output_path: Path,
    keypair: KeyPair,
    detection_score: float = 0.04,
    detection_label: str = "GENUINE",
    detection_model: str = "pramana-mobilenet-v3-small-int8-v1",
    detection_backend: str = "NPU",
    device_model: str = "Pramana-CLI",
    device_fingerprint: Optional[str] = None,
    iso: int = 100,
    exposure_us: int = 8333,
    focal_length_mm: float = 4.38,
    jpeg_quality: int = 92,
) -> SealedFile:
    """Read input_path, seal it, and write to output_path.

    Returns the SealedFile with the manifest that was embedded.
    """
    if device_fingerprint is None:
        device_fingerprint = _sha256_hex(b"cli-dev-device")

    # 1. Decode and align to 8x8 grid
    img = Image.open(input_path).convert("RGB")
    rgb = np.asarray(img)
    rgb = crop_to_8(rgb)
    h, w, _ = rgb.shape

    # 2. Build skeleton manifest (placeholder content hash) → JCS → DCT payload
    pubkey_der = keypair.public_der()
    key_id_hex = _sha256_hex(pubkey_der)
    skel_manifest = _build_manifest(
        captured_at_ms=int(time.time() * 1000),
        device_model=device_model,
        device_fingerprint=device_fingerprint,
        iso=iso,
        exposure_us=exposure_us,
        focal_length_mm=focal_length_mm,
        content_hash="0" * 64,
        detection_score=detection_score,
        detection_label=detection_label,
        detection_model=detection_model,
        detection_backend=detection_backend,
        key_id=key_id_hex,
    )
    skel_jcs = canonicalize_obj(skel_manifest.to_json_dict_for_signing())
    payload = build_payload(skel_jcs, key_id_hex)

    # 3. Embed DCT watermark in Y channel
    y, cb, cr = rgb_to_ycbcr(rgb)
    embed(y, payload)
    rgb_marked = ycbcr_to_rgb(y, cb, cr)

    # 4. Re-encode to JPEG → contentHash on the canonical decoded pixels
    jpeg_bytes = _rgb_to_jpeg_bytes(rgb_marked, quality=jpeg_quality)
    canonical_pixels = _jpeg_to_rgb(jpeg_bytes)
    # Match Kotlin: hash of the raw packed RGB bytes
    content_hash = _sha256_hex(canonical_pixels.tobytes())

    # 5. Real manifest, JCS, sign
    unsigned = _build_manifest(
        captured_at_ms=skel_manifest.captured_at,    # reuse same timestamp
        device_model=device_model,
        device_fingerprint=device_fingerprint,
        iso=iso,
        exposure_us=exposure_us,
        focal_length_mm=focal_length_mm,
        content_hash=content_hash,
        detection_score=detection_score,
        detection_label=detection_label,
        detection_model=detection_model,
        detection_backend=detection_backend,
        key_id=key_id_hex,
    )
    canon = canonicalize_obj(unsigned.to_json_dict_for_signing())
    signature_der = sign(keypair, canon)
    signed = Manifest(
        captured_at=unsigned.captured_at,
        device_fingerprint=unsigned.device_fingerprint,
        sensor=unsigned.sensor,
        content_hash=unsigned.content_hash,
        detection=unsigned.detection,
        key_id=unsigned.key_id,
        signature=base64.b64encode(signature_der).decode("ascii"),
    )

    # 6. Write JPEG → embed manifest in EXIF UserComment
    output_path.write_bytes(jpeg_bytes)
    signed_json = json.dumps(signed.to_json_dict()).encode("utf-8")
    embed_user_comment(output_path, signed_json)
    return SealedFile(bytes_=output_path.read_bytes(), manifest=signed)


def _build_manifest(
    *,
    captured_at_ms,
    device_model,
    device_fingerprint,
    iso,
    exposure_us,
    focal_length_mm,
    content_hash,
    detection_score,
    detection_label,
    detection_model,
    detection_backend,
    key_id,
) -> Manifest:
    """Build manifest with all numerics stringified per bible v1.1 hardening."""
    return Manifest(
        captured_at=str(captured_at_ms),
        device_fingerprint=device_fingerprint,
        sensor=SensorMeta(
            model=device_model,
            iso=str(iso),
            exposure_us=str(exposure_us),
            focal_length_mm=str(focal_length_mm),
        ),
        content_hash=content_hash,
        detection=DetectionMeta(
            score=str(detection_score),
            label=detection_label,
            model=detection_model,
            backend=detection_backend,
        ),
        key_id=key_id,
    )
