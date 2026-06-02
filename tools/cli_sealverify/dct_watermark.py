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
# Larger QIM step than the original 12 — mid-frequency coefficients tolerate it
# and it must exceed the JPEG quantization step at q60-70 to survive recompression.
DELTA = 28.0
# Each codeword bit is embedded into up to this many 8x8 blocks; on extract we
# majority-vote. Redundancy adapts to image size (R = blocks // CODEWORD_BITS,
# clamped). A multi-megapixel capture gets large R -> survives heavy recompression.
MAX_REDUNDANCY = 64

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


def _redundancy(blocks_x: int, blocks_y: int) -> int:
    """R = how many blocks carry each codeword bit (clamped). Deterministic from
    image dims, so the extractor recomputes the same R from the same dims."""
    r = (blocks_x * blocks_y) // CODEWORD_BITS
    return max(1, min(MAX_REDUNDANCY, r))


def _codeword_bit(codeword: bytes, bit: int) -> int:
    return (codeword[bit // 8] >> (7 - (bit % 8))) & 1


def embed(y: np.ndarray, payload: bytes) -> None:
    """Embed `payload` into the Y plane in-place with adaptive redundancy.

    Each of the 384 codeword bits is written into R blocks (R from image size),
    interleaved so a bit's copies spread across the frame. `y` shape (H,W) float32,
    H and W multiples of 8, at least CODEWORD_BITS blocks.
    """
    assert len(payload) == PAYLOAD_BYTES
    codeword = rs_encode(payload, ECC_BYTES)
    h, w = y.shape
    blocks_x = w // 8
    blocks_y = h // 8
    assert blocks_x * blocks_y >= CODEWORD_BITS, (
        f"image too small: {blocks_x*blocks_y} blocks, need {CODEWORD_BITS}"
    )
    r = _redundancy(blocks_x, blocks_y)
    total = CODEWORD_BITS * r
    q = 2 * DELTA
    for slot in range(total):
        bit = slot % CODEWORD_BITS          # interleaved: copies of a bit are 384 slots apart
        bx = slot % blocks_x
        by = slot // blocks_x
        block = y[by*8:by*8+8, bx*8:bx*8+8].astype(np.float64)
        flat = _zigzag_flat(_dct8(block))
        want = _codeword_bit(codeword, bit)
        cur = flat[EMBED_FLAT_INDEX]
        if want == 1:
            target = round((cur - DELTA) / q) * q + DELTA
        else:
            target = round(cur / q) * q
        flat[EMBED_FLAT_INDEX] = target
        recon = _idct8(_zigzag_unflat(flat))
        y[by*8:by*8+8, bx*8:bx*8+8] = np.clip(recon, 0, 255).astype(np.float32)


def extract(y: np.ndarray) -> Tuple[str, bytes]:
    """Returns ('found', payload) | ('damaged', codeword) | ('none', b'').
    Reads every slot, majority-votes each bit across its R copies."""
    h, w = y.shape
    blocks_x = w // 8
    blocks_y = h // 8
    if blocks_x * blocks_y < CODEWORD_BITS:
        return ("none", b"")
    r = _redundancy(blocks_x, blocks_y)
    total = CODEWORD_BITS * r
    q = 2 * DELTA
    votes = [0] * CODEWORD_BITS              # count of '1' reads per bit
    for slot in range(total):
        bit = slot % CODEWORD_BITS
        bx = slot % blocks_x
        by = slot // blocks_x
        block = y[by*8:by*8+8, bx*8:bx*8+8].astype(np.float64)
        coef = _zigzag_flat(_dct8(block))[EMBED_FLAT_INDEX]
        to_even = abs(coef - round(coef / q) * q)
        to_odd = abs(coef - (round((coef - DELTA) / q) * q + DELTA))
        if to_odd < to_even:
            votes[bit] += 1

    codeword = bytearray(CODEWORD_BYTES)
    any_one = False
    for bit in range(CODEWORD_BITS):
        if votes[bit] * 2 > r:               # strict majority of the R copies
            codeword[bit // 8] |= (1 << (7 - (bit % 8)))
            any_one = True
    if not any_one:
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
