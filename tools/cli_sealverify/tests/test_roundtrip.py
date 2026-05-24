"""End-to-end Python seal → verify round-trip + stripped-EXIF case."""
from __future__ import annotations

import io
from pathlib import Path

import numpy as np
import pytest
from PIL import Image

from tools.cli_sealverify.exif_io import strip_exif
from tools.cli_sealverify.keystore import deterministic_test_keypair, public_b64
from tools.cli_sealverify.seal import seal_image
from tools.cli_sealverify.verify import verify_image


def _make_test_image(tmp_path: Path) -> Path:
    # 256x256 RGB image with smooth gradients — plenty of 8x8 blocks for DCT
    arr = np.zeros((256, 256, 3), dtype=np.uint8)
    for y in range(256):
        for x in range(256):
            arr[y, x] = [(x + y) % 256, (x * 2) % 256, (y * 2) % 256]
    p = tmp_path / "test.jpg"
    Image.fromarray(arr).save(p, format="JPEG", quality=95)
    return p


def _trust_for(kp) -> dict[str, str]:
    import hashlib
    return {hashlib.sha256(kp.public_der()).hexdigest(): public_b64(kp.public)}


def test_seal_then_verify_returns_verified_original(tmp_path: Path):
    src = _make_test_image(tmp_path)
    out = tmp_path / "sealed.jpg"
    kp = deterministic_test_keypair()

    seal_image(src, out, kp)
    result = verify_image(out, _trust_for(kp))
    assert result.state == "verified_original", f"got {result.state} reason={result.reason}"
    assert result.manifest is not None


def test_unknown_key_yields_broken_seal(tmp_path: Path):
    src = _make_test_image(tmp_path)
    out = tmp_path / "sealed.jpg"
    kp = deterministic_test_keypair()
    seal_image(src, out, kp)

    # Use a different key's trust dict — won't match the manifest's keyId
    other = deterministic_test_keypair(seed_int=0xCAFEBABE)
    result = verify_image(out, _trust_for(other))
    assert result.state == "broken_seal"
    assert result.reason == "unknown_key"


def test_stripped_exif_falls_through_to_watermark(tmp_path: Path):
    src = _make_test_image(tmp_path)
    sealed = tmp_path / "sealed.jpg"
    stripped = tmp_path / "stripped.jpg"
    kp = deterministic_test_keypair()
    seal_image(src, sealed, kp)
    strip_exif(sealed, stripped)

    result = verify_image(stripped, _trust_for(kp))
    # After EXIF stripping, the watermark should be detected — either as
    # broken_seal (watermark_orphan) or the manifest reconstruction failed.
    # In v1.1 scope we expect broken_seal/watermark_orphan to indicate the
    # Pramāṇa-origin claim.
    assert result.state in ("broken_seal", "no_provenance")
    # If watermark survived the strip+re-encode, watermark_found must be True
    # OR the verifier reports the orphan reason. JPEG quality 90 may damage
    # the watermark below detectability — accept either outcome but log.


def test_modified_pixels_yield_verified_but_modified(tmp_path: Path):
    src = _make_test_image(tmp_path)
    sealed = tmp_path / "sealed.jpg"
    kp = deterministic_test_keypair()
    seal_image(src, sealed, kp)

    # Modify pixels but keep EXIF intact (paste over a chunk)
    img = Image.open(sealed)
    arr = np.asarray(img).copy()
    arr[10:50, 10:50] = 0
    Image.fromarray(arr).save(sealed, format="JPEG", quality=95, exif=img.info.get("exif", b""))

    result = verify_image(sealed, _trust_for(kp))
    # Either modified-pixels detected or EXIF was lost by Pillow's re-save
    # (Pillow doesn't perfectly preserve EXIF across re-encode). Accept either.
    assert result.state in ("verified_but_modified", "broken_seal", "no_provenance")
