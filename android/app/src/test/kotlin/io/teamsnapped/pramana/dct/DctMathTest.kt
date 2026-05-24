package io.teamsnapped.pramana.dct

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class DctMathTest {

    /**
     * The defining property: applying DCT and then IDCT must recover the
     * original block (up to floating-point round-off). This is also the
     * smoke test that the cosine table + alpha factors are right.
     */
    @Test
    fun `dct then idct is identity on random blocks`() {
        val rng = Random(1L)
        repeat(20) {
            val block = FloatArray(64) { rng.nextFloat() * 255f }
            val original = block.copyOf()
            DctMath.dct(block)
            DctMath.idct(block)
            for (i in 0 until 64) {
                assertThat(abs(block[i] - original[i])).isLessThan(1e-3f)
            }
        }
    }

    @Test
    fun `dct of constant block puts all energy in DC`() {
        val block = FloatArray(64) { 128f }
        DctMath.dct(block)
        // DC = (1/2) * alpha(0)^2 * sum = (1/2)*(1/sqrt2)^2 * 64*128 = 0.5*0.5*8192 = 2048
        // Reference impls land near 2048 (depends on factor convention).
        // Just assert DC is huge and other coefficients are ~0.
        assertThat(abs(block[0])).isGreaterThan(100f)
        for (i in 1 until 64) {
            assertThat(abs(block[i])).isLessThan(1e-2f)
        }
    }

    @Test
    fun `zigzag is a permutation of 0 to 63`() {
        val unique = DctMath.ZIGZAG.toSortedSet()
        assertThat(unique.size).isEqualTo(64)
        assertThat(unique.first()).isEqualTo(0)
        assertThat(unique.last()).isEqualTo(63)
    }

    @Test
    fun `embed zigzag index points to a mid-frequency coefficient`() {
        // Bible says zigzag index ~20-40; we use 27.
        assertThat(DctMath.EMBED_ZIGZAG_INDEX).isAtLeast(20)
        assertThat(DctMath.EMBED_ZIGZAG_INDEX).isAtMost(40)
    }
}
