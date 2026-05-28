"""Reed-Solomon over GF(256) — Python mirror of Kotlin `dct/ReedSolomon.kt`.

Same generator polynomial (AES 0x11d), same encoding rule, same syndrome
check. Test vectors in `tools/test_vectors/` validate that an Android-encoded
codeword decodes intact on the Python side.
"""
from __future__ import annotations

POLY = 0x11D
GF_EXP = [0] * 512
GF_LOG = [0] * 256


def _init_tables() -> None:
    x = 1
    for i in range(255):
        GF_EXP[i] = x
        GF_LOG[x] = i
        x <<= 1
        if x & 0x100:
            x ^= POLY
    for i in range(255, 512):
        GF_EXP[i] = GF_EXP[i - 255]


_init_tables()


def _gf_mul(a: int, b: int) -> int:
    if a == 0 or b == 0:
        return 0
    return GF_EXP[GF_LOG[a] + GF_LOG[b]]


def _generator(ecc_len: int) -> list[int]:
    g = [1]
    for i in range(ecc_len):
        nxt = [0] * (len(g) + 1)
        for j in range(len(g)):
            nxt[j] ^= g[j]
            nxt[j + 1] ^= _gf_mul(g[j], GF_EXP[i])
        g = nxt
    return g


def encode(data: bytes, ecc_len: int) -> bytes:
    assert len(data) + ecc_len <= 255
    gen = _generator(ecc_len)            # monic, gen[0] == 1, length ecc_len + 1

    # Systematic encoding by synthetic polynomial division of
    # data(x)*x^ecc_len by gen(x). The remainder is the parity; appending it
    # makes the codeword divisible by gen(x), so every syndrome is zero.
    res = list(data) + [0] * ecc_len
    for i in range(len(data)):
        coef = res[i]
        if coef != 0:
            # gen[0] is the monic leading 1 — skip it.
            for j in range(1, len(gen)):
                res[i + j] ^= _gf_mul(gen[j], coef)
    return bytes(data) + bytes(res[len(data):])


def is_intact(codeword: bytes, ecc_len: int) -> bool:
    for i in range(ecc_len):
        s = 0
        for byte in codeword:
            s = _gf_mul(s, GF_EXP[i]) ^ byte
        if s != 0:
            return False
    return True
