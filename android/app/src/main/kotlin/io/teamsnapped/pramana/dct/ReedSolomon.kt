package io.teamsnapped.pramana.dct

/**
 * Minimal Reed-Solomon over GF(256) — encoder + syndrome-based detector.
 *
 * Bible Section 7: "Error-correcting redundancy bits (Reed-Solomon over
 * ~half the payload — survives partial pixel corruption)."
 *
 * Scope intentionally small:
 *  - Encode: appends `eccLen` parity bytes to the data.
 *  - Detect: validates syndrome == 0. If non-zero, we report "watermark
 *    damaged" and the verifier reports `WATERMARK_ORPHAN`. Full Berlekamp-
 *    Massey error correction is a stretch (hackathon-time vs polish-time).
 *
 * Uses the AES-style irreducible polynomial 0x11d (x^8 + x^4 + x^3 + x + 1)
 * — same as ZXing QR Reed-Solomon, so test vectors are interoperable if we
 * ever need to swap implementations.
 */
internal object ReedSolomon {

    private const val POLY = 0x11d
    private val GF_EXP = IntArray(512)
    private val GF_LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            GF_EXP[i] = x
            GF_LOG[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor POLY
        }
        for (i in 255 until 512) GF_EXP[i] = GF_EXP[i - 255]
    }

    private fun gfMul(a: Int, b: Int): Int =
        if (a == 0 || b == 0) 0 else GF_EXP[GF_LOG[a] + GF_LOG[b]]

    /** Build generator polynomial g(x) = ∏ (x - α^i) for i in 0..eccLen-1. */
    private fun generator(eccLen: Int): IntArray {
        var g = intArrayOf(1)
        for (i in 0 until eccLen) {
            val next = IntArray(g.size + 1)
            for (j in g.indices) {
                next[j] = next[j] xor g[j]
                next[j + 1] = next[j + 1] xor gfMul(g[j], GF_EXP[i])
            }
            g = next
        }
        return g
    }

    /**
     * Returns `data || parity` (length = data.size + eccLen).
     * The parity bytes are appended to the data and recovered identically
     * by the verifier.
     */
    fun encode(data: ByteArray, eccLen: Int): ByteArray {
        require(data.size + eccLen <= 255) { "RS(n=255) — data + ecc must fit in 255 bytes" }
        val gen = generator(eccLen)
        val parity = IntArray(eccLen)
        for (b in data) {
            val factor = (b.toInt() and 0xff) xor parity[0]
            for (i in 0 until eccLen - 1) {
                parity[i] = parity[i + 1] xor gfMul(factor, gen[gen.size - 2 - i])
            }
            parity[eccLen - 1] = gfMul(factor, gen[0])
        }
        val out = ByteArray(data.size + eccLen)
        System.arraycopy(data, 0, out, 0, data.size)
        for (i in 0 until eccLen) out[data.size + i] = parity[i].toByte()
        return out
    }

    /**
     * Returns true iff the syndromes are all zero — i.e. no detectable
     * corruption. Returns false on any bit-flip; we do NOT attempt to
     * recover.
     */
    fun isIntact(codeword: ByteArray, eccLen: Int): Boolean {
        for (i in 0 until eccLen) {
            var s = 0
            for (b in codeword) {
                s = gfMul(s, GF_EXP[i]) xor (b.toInt() and 0xff)
            }
            if (s != 0) return false
        }
        return true
    }
}
