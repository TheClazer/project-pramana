package io.teamsnapped.pramana.dct

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Textbook 2D DCT-II / IDCT for 8×8 blocks.
 *
 * Bible Section 13 marks DCT math as VIBE-safe ("classical 8×8 block DCT is
 * textbook signal processing"). We verify against a reference impl by
 * round-tripping known payloads through JPEG-recompression — see the unit
 * tests in `dct/DctWatermarkTest`.
 *
 * Operates in-place on a 64-float array (row-major). Float, not double —
 * JPEG quantization swamps the precision difference and Float is faster.
 *
 * The cosine table is precomputed because we run this on a lot of blocks
 * (one per bit of payload).
 */
internal object DctMath {

    private const val N = 8
    private const val INV_SQRT2 = 0.7071067811865476f

    private val COS = Array(N) { u ->
        FloatArray(N) { x ->
            cos(((2 * x + 1) * u * Math.PI) / (2.0 * N)).toFloat()
        }
    }

    private fun alpha(u: Int): Float = if (u == 0) INV_SQRT2 else 1f

    /** Forward DCT (in-place). */
    fun dct(block: FloatArray) {
        require(block.size == 64) { "block must be 8x8" }
        val tmp = FloatArray(64)
        for (u in 0 until N) {
            for (v in 0 until N) {
                var sum = 0f
                for (x in 0 until N) {
                    for (y in 0 until N) {
                        sum += block[y * N + x] * COS[u][x] * COS[v][y]
                    }
                }
                tmp[v * N + u] = 0.25f * alpha(u) * alpha(v) * sum
            }
        }
        System.arraycopy(tmp, 0, block, 0, 64)
    }

    /** Inverse DCT (in-place). */
    fun idct(coeff: FloatArray) {
        require(coeff.size == 64) { "block must be 8x8" }
        val tmp = FloatArray(64)
        for (x in 0 until N) {
            for (y in 0 until N) {
                var sum = 0f
                for (u in 0 until N) {
                    for (v in 0 until N) {
                        sum += alpha(u) * alpha(v) * coeff[v * N + u] *
                                COS[u][x] * COS[v][y]
                    }
                }
                tmp[y * N + x] = 0.25f * sum
            }
        }
        System.arraycopy(tmp, 0, coeff, 0, 64)
    }

    /**
     * JPEG zigzag scan order — used to pick mid-frequency coefficients.
     *
     * Index 0 is DC; 1..63 are AC in zigzag order. Bible Section 7 says
     * "zigzag index ~20–40" — we use 27 (a balance between perceptual
     * invisibility and JPEG-survival robustness).
     */
    val ZIGZAG = intArrayOf(
        0,  1,  8, 16,  9,  2,  3, 10,
       17, 24, 32, 25, 18, 11,  4,  5,
       12, 19, 26, 33, 40, 48, 41, 34,
       27, 20, 13,  6,  7, 14, 21, 28,
       35, 42, 49, 56, 57, 50, 43, 36,
       29, 22, 15, 23, 30, 37, 44, 51,
       58, 59, 52, 45, 38, 31, 39, 46,
       53, 60, 61, 54, 47, 55, 62, 63
    )

    /** Index into the flat 8×8 coefficient array for embedding a bit. */
    const val EMBED_ZIGZAG_INDEX = 27
}
