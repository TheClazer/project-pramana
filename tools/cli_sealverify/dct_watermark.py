"""DCT 8×8 block watermark — Python mirror of Kotlin `dct/DctWatermark.kt`.

Same constants (DELTA=12, zigzag index 27, payload=32 B + ECC=16 B),
same embedding/extraction algorithm. Used by the CLI to round-trip and
to generate cross-language test vectors.
"""
from __future__ import annotations

import hashlib
from typing import Tuple

import numpy as np
from scipy.fftpack import dct, idct

from .reed_solomon import encode as rs_encode, is_intact as rs_intact

VERSION_BYTE = 0x01
PAYLOAD_BYTES = 32
ECC_BYTES = 16
CODEWORD_BYTES = PAYLOAD_BYTES + ECC_BYTES
CODEWORD_BITS = CODEWORD_BYTES * 8           # 384
DELTA = 12.0

# JPEG zigzag scan — mid-frequency index 27 per bible §7
ZIGZAG = np.array([
    0,  1,  8, 16,  9,  2,  3, 10,
    17, 24, 32, 25, 18, 11,  4,  5,
    12, 19, 26, 33, 40, 48, 41, 34,
    27, 20, 13,  6,  7, 14, 21, 28,
    35, 42, 49, 56, 57, 50, 43, 36,
    29, 22, 15, 23, 30, 37, 44, 51,
    58, 59, 52, 45, 38, 31, 39, 46,
    53, 60, 61, 54, 47, 55, 62, 63
], dtype=np.int32)
EMBED_ZIGZAG_INDEX = 27
EMBED_FLAT_INDEX = int(ZIGZAG[EMBED_ZIGZAG_INDEX])


def build_payload(manifest_jcs_bytes: bytes, key_id_hex: str) -> bytes:
    assert len(key_id_hex) >= 16
    out = bytearray(PAYLOAD_BYTES)
    out[0] = VERSION_BYTE
    fp = hashlib.sha256(manifest_jcs_bytes).digest()[:16]
    out[1:17] = fp
    out[17:25] = bytes.fromhex(key_id_hex[:16])
    # Bytes 25..31 reserved 0
    return bytes(out)


def _dct8(block: np.ndarray) -> np.ndarray:
    return dct(dct(block, axis=0, norm="ortho"), axis=1, norm="ortho")


def _idct8(block: np.ndarray) -> np.ndarray:
    return idct(idct(block, axis=0, norm="ortho"), axis=1, norm="ortho")


def _zigzag_flat(block: np.ndarray) -> np.ndarray:
    return block.flatten()


def _zigzag_unflat(flat: np.ndarray) -> np.ndarray:
    return flat.reshape(8, 8)


def embed(y: np.ndarray, payload: bytes) -> None:
    """Embed `payload` (PAYLOAD_BYTES) into the Y plane in-place.

    `y` shape: (H, W) float32. H and W must be multiples of 8 and there
    must be at least CODEWORD_BITS 8×8 blocks (~7×7 pixels minimum each).
    """
    assert len(payload) == PAYLOAD_BYTES
    codeword = rs_encode(payload, ECC_BYTES)
    h, w = y.shape
    blocks_x = w // 8
    blocks_y = h // 8
    assert blocks_x * blocks_y >= CODEWORD_BITS, (
        f"image too small: {blocks_x*blocks_y} blocks, need {CODEWORD_BITS}"
    )

    q = 2 * DELTA
    for bit in range(CODEWORD_BITS):
        bx = bit % blocks_x
        by = bit // blocks_x
        block = y[by*8:by*8+8, bx*8:bx*8+8].astype(np.float64)
        coeffs = _dct8(block)
        flat = _zigzag_flat(coeffs)

        byte_idx = bit // 8
        bit_in_byte = 7 - (bit % 8)
        want = (codeword[byte_idx] >> bit_in_byte) & 1

        cur = flat[EMBED_FLAT_INDEX]
        if want == 1:
            n = round((cur - DELTA) / q)
            target = n * q + DELTA
        else:
            n = round(cur / q)
            target = n * q
        flat[EMBED_FLAT_INDEX] = target

        coeffs = _zigzag_unflat(flat)
        recon = _idct8(coeffs)
        y[by*8:by*8+8, bx*8:bx*8+8] = np.clip(recon, 0, 255).astype(np.float32)


def extract(y: np.ndarray) -> Tuple[str, bytes]:
    """Returns ('found', payload) | ('damaged', codeword) | ('none', b'')."""
    h, w = y.shape
    blocks_x = w // 8
    blocks_y = h // 8
    if blocks_x * blocks_y < CODEWORD_BITS:
        return ("none", b"")

    codeword = bytearray(CODEWORD_BYTES)
    any_nonzero = False
    q = 2 * DELTA
    for bit in range(CODEWORD_BITS):
        bx = bit % blocks_x
        by = bit // blocks_x
        block = y[by*8:by*8+8, bx*8:bx*8+8].astype(np.float64)
        coeffs = _dct8(block)
        flat = _zigzag_flat(coeffs)
        coef = flat[EMBED_FLAT_INDEX]

        to_even = abs(coef - round(coef / q) * q)
        to_odd  = abs(coef - (round((coef - DELTA) / q) * q + DELTA))
        bit_val = 1 if to_odd < to_even else 0
        if bit_val:
            any_nonzero = True
            byte_idx = bit // 8
            bit_in_byte = 7 - (bit % 8)
            codeword[byte_idx] |= (1 << bit_in_byte)

    if not any_nonzero:
        return ("none", b"")
    if rs_intact(bytes(codeword), ECC_BYTES):
        return ("found", bytes(codeword[:PAYLOAD_BYTES]))
    return ("damaged", bytes(codeword))


# ----------------------------- RGB ⇄ YCbCr ----------------------------- #
def rgb_to_ycbcr(rgb: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """BT.601 — matches Kotlin YCbCr.fromRgb()."""
    r, g, b = rgb[..., 0].astype(np.float32), rgb[..., 1].astype(np.float32), rgb[..., 2].astype(np.float32)
    y  =  0.299 * r + 0.587 * g + 0.114 * b
    cb = -0.168736 * r - 0.331264 * g + 0.5 * b + 128.0
    cr =  0.5 * r - 0.418688 * g - 0.081312 * b + 128.0
    return y, cb, cr


def ycbcr_to_rgb(y: np.ndarray, cb: np.ndarray, cr: np.ndarray) -> np.ndarray:
    cb_c = cb - 128.0
    cr_c = cr - 128.0
    r = np.clip(y + 1.402   * cr_c, 0, 255)
    g = np.clip(y - 0.344136 * cb_c - 0.714136 * cr_c, 0, 255)
    b = np.clip(y + 1.772   * cb_c, 0, 255)
    return np.stack([r, g, b], axis=-1).astype(np.uint8)


def crop_to_8(rgb: np.ndarray) -> np.ndarray:
    h, w, _ = rgb.shape
    return rgb[: (h // 8) * 8, : (w // 8) * 8, :]
