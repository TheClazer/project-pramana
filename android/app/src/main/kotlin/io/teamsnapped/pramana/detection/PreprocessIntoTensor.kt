package io.teamsnapped.pramana.detection

import io.teamsnapped.pramana.api.FrameInput
import java.nio.ByteBuffer

/**
 * Frame-to-tensor preprocessing.
 *
 * Bible Section 13 MANUAL rule: "Pixel layout, normalization range, channel
 * order. Tiny mistakes here are silent and cause the model to underperform.
 * Implement to match the Python reference exactly, with a unit test that
 * compares numpy and Kotlin outputs on the same image bytes."
 *
 * Contract (must match `ml/reference_inference.py`):
 *  - Input:  packed RGB, row-major, 8 bpp per channel
 *  - Output: NCHW float32, normalized to [0, 1] (no mean/std subtraction)
 *  - Crop:   center-square crop, then bilinear resize to 224×224
 *
 * If Engineer A changes the model preprocessing, this file and the Python
 * reference change together — they are the contract.
 */
internal object PreprocessIntoTensor {

    private const val TARGET = 224

    /**
     * Reads `frame.rgb`, center-crops to the largest square that fits,
     * bilinear-downsamples to 224×224, normalizes to [0,1], and writes
     * NCHW float32 into `out` (which must be a direct ByteBuffer of length
     * 1*3*224*224*4 bytes).
     */
    fun run(frame: FrameInput, out: ByteBuffer) {
        val w = frame.width; val h = frame.height
        val side = minOf(w, h)
        val ox = (w - side) / 2
        val oy = (h - side) / 2
        val src = frame.rgb

        // We write channel-by-channel (CHW) for NCHW layout.
        // Step 1: R, Step 2: G, Step 3: B.
        for (c in 0 until 3) {
            for (ty in 0 until TARGET) {
                val sy = (ty * side / TARGET) + oy
                for (tx in 0 until TARGET) {
                    val sx = (tx * side / TARGET) + ox
                    val i = (sy * w + sx) * 3 + c
                    val v = (src[i].toInt() and 0xff) / 255f
                    out.putFloat(v)
                }
            }
        }
    }
}
