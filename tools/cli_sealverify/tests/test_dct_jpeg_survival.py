"""The robustness test that was MISSING and let the watermark ship broken.

Embeds a payload, JPEG-compresses at realistic qualities (PIL), re-decodes, and
re-extracts. Pins the honest claim: the DCT watermark survives RE-COMPRESSION at
the SAME resolution down to ~q60. (A RESIZE defeats it — that's the EXIF manifest's
job — and is asserted separately so the limitation stays documented, not hidden.)
"""
from __future__ import annotations

import io

import numpy as np
import pytest
from PIL import Image

from tools.cli_sealverify import dct_watermark as dw

PAYLOAD = bytes((i * 13 + 5) % 256 for i in range(dw.PAYLOAD_BYTES))


def _make_img(h: int, w: int, seed: int = 7) -> np.ndarray:
    rng = np.random.RandomState(seed)
    yy, xx = np.mgrid[0:h, 0:w]
    base = np.zeros((h, w, 3), np.float32)
    base[..., 0] = 120 + 60 * np.sin(xx / 30)
    base[..., 1] = 110 + 50 * np.cos(yy / 25)
    base[..., 2] = 100 + 40 * np.sin((xx + yy) / 40)
    return np.clip(base + rng.randn(h, w, 3) * 4, 0, 255).astype(np.uint8)


def _seal(rgb: np.ndarray) -> np.ndarray:
    y, cb, cr = dw.rgb_to_ycbcr(rgb.astype(np.float32))
    dw.embed(y, PAYLOAD)
    return dw.ycbcr_to_rgb(y, cb, cr)


def _recompress_extract(marked: np.ndarray, quality: int):
    buf = io.BytesIO()
    Image.fromarray(marked).save(buf, format="JPEG", quality=quality)
    buf.seek(0)
    rgb2 = dw.crop_to_8(np.asarray(Image.open(buf).convert("RGB")))
    y2, _, _ = dw.rgb_to_ycbcr(rgb2.astype(np.float32))
    return dw.extract(y2)


@pytest.mark.parametrize("size", [(480, 640), (720, 960), (960, 1280)])
@pytest.mark.parametrize("quality", [95, 85, 75, 70, 65, 60])
def test_survives_recompression_same_resolution(size, quality):
    h, w = size
    marked = _seal(_make_img(h, w))
    kind, out = _recompress_extract(marked, quality)
    assert kind == "found", f"{w}x{h} q{quality}: expected found, got {kind}"
    assert out == PAYLOAD, f"{w}x{h} q{quality}: payload mismatch"


def test_imperceptible_psnr_above_38db():
    h, w = 720, 960
    orig = _make_img(h, w).astype(np.float32)
    marked = _seal(_make_img(h, w)).astype(np.float32)
    mse = float(np.mean((orig - marked) ** 2))
    psnr = 10 * np.log10(255 * 255 / mse) if mse > 0 else 99.0
    assert psnr > 38.0, f"watermark too visible: PSNR {psnr:.1f} dB"


def test_resize_defeats_watermark_is_documented():
    """Honest negative test: a downscale changes the 8x8 block grid and destroys
    the watermark. This is EXPECTED — the EXIF manifest is the channel that
    survives resize. If this ever starts passing, update the README claim."""
    marked = _seal(_make_img(960, 1280))
    img = Image.fromarray(marked).resize((960, 720), Image.BILINEAR)
    buf = io.BytesIO(); img.save(buf, format="JPEG", quality=80); buf.seek(0)
    rgb2 = dw.crop_to_8(np.asarray(Image.open(buf).convert("RGB")))
    y2, _, _ = dw.rgb_to_ycbcr(rgb2.astype(np.float32))
    kind, _ = dw.extract(y2)
    assert kind != "found", "resize unexpectedly survived — update the honest claim in README/bible"
