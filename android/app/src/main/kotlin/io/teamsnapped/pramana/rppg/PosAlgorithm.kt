package io.teamsnapped.pramana.rppg

import kotlin.math.sqrt

/**
 * POS (Plane-Orthogonal-to-Skin) — Wang et al. 2017.
 *
 * Projects an RGB signal onto a skin-tone-invariant plane, isolating the
 * pulsatile component. Bible Section 6: "POS algorithm projects the RGB
 * signal onto a skin-tone-invariant plane, isolating the pulsatile
 * component."
 *
 * Operates on a sliding window of N frames per ROI. Standard reference
 * implementation; the math is in the paper.
 *
 * Returns the POS-projected 1D signal of length N. The caller runs an FFT
 * over this signal to detect the heart-band peak.
 */
internal object PosAlgorithm {

    /** Project the RGB time series (each of length N) onto the POS axis. */
    fun project(red: FloatArray, green: FloatArray, blue: FloatArray): FloatArray {
        require(red.size == green.size && green.size == blue.size) { "RGB lengths must match" }
        val n = red.size

        // Normalize each channel by its temporal mean.
        val meanR = red.average().toFloat().coerceAtLeast(1e-6f)
        val meanG = green.average().toFloat().coerceAtLeast(1e-6f)
        val meanB = blue.average().toFloat().coerceAtLeast(1e-6f)

        val rn = FloatArray(n) { red[it]   / meanR - 1f }
        val gn = FloatArray(n) { green[it] / meanG - 1f }
        val bn = FloatArray(n) { blue[it]  / meanB - 1f }

        // Two orthogonal projection axes (POS paper, Eq. 5).
        val x = FloatArray(n) { gn[it] - bn[it] }                  // 3R - 2G  proxy
        val y = FloatArray(n) { -2f * rn[it] + gn[it] + bn[it] }   // -2*L1   proxy

        val sX = std(x)
        val sY = std(y)
        val alpha = if (sY > 1e-6f) sX / sY else 1f

        return FloatArray(n) { x[it] + alpha * y[it] }
    }

    private fun std(a: FloatArray): Float {
        val m = a.average().toFloat()
        var s = 0f
        for (v in a) s += (v - m) * (v - m)
        return sqrt(s / a.size)
    }
}
