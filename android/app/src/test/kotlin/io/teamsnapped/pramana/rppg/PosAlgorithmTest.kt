package io.teamsnapped.pramana.rppg

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Sanity checks on the POS projection. Bible §6: POS isolates the pulsatile
 * component from RGB time series. We can't fully validate without a real
 * pulse trace; these are correctness/identity guards.
 */
class PosAlgorithmTest {

    @Test
    fun `flat signals project to ~zero`() {
        val n = 30
        val red = FloatArray(n) { 150f }
        val green = FloatArray(n) { 100f }
        val blue = FloatArray(n) { 80f }
        val out = PosAlgorithm.project(red, green, blue)
        for (v in out) assertThat(kotlin.math.abs(v)).isLessThan(1e-3f)
    }

    @Test
    fun `synthetic pulsed signal projects to non-trivial variance`() {
        // Embed a 1 Hz pulse in green at 30 fps.
        val n = 30
        val fs = 30f
        val pulseHz = 1f
        val red = FloatArray(n) { 150f + 0.5f * sin(2.0 * PI * 0.1 * it / fs).toFloat() }
        val green = FloatArray(n) { 100f + 2.0f * sin(2.0 * PI * pulseHz * it / fs).toFloat() }
        val blue = FloatArray(n) { 80f + 0.3f * sin(2.0 * PI * 0.2 * it / fs).toFloat() }
        val out = PosAlgorithm.project(red, green, blue)
        val mean = out.average().toFloat()
        var variance = 0f
        for (v in out) variance += (v - mean) * (v - mean)
        variance /= n
        // Should produce meaningful variance — far more than 0 (which we'd get
        // if the projection collapsed). The exact value depends on alpha.
        assertThat(variance).isGreaterThan(1e-4f)
    }

    @Test
    fun `output length matches input length`() {
        val n = 30
        val r = FloatArray(n) { it.toFloat() }
        val g = FloatArray(n) { (n - it).toFloat() }
        val b = FloatArray(n) { 50f }
        val out = PosAlgorithm.project(r, g, b)
        assertThat(out.size).isEqualTo(n)
    }

    @Test
    fun `mismatched lengths throw`() {
        val r = FloatArray(30) { 100f }
        val g = FloatArray(29) { 100f }
        val b = FloatArray(30) { 100f }
        try {
            PosAlgorithm.project(r, g, b)
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
