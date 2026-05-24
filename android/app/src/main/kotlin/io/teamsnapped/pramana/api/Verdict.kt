package io.teamsnapped.pramana.api

/**
 * Detection verdict — what comes out of [DetectionEngine.analyze].
 *
 * Bible Section 6, Appendix A. The fusion layer combines NPU classifier score
 * and rPPG liveness score into a single label using the rules in
 * `detection/Fusion.kt`.
 *
 * The `confidence` semantics:
 *  - For GENUINE: higher = more confident it's real
 *  - For FAKE:    higher = more confident it's fake
 *  - For SUSPICIOUS: confidence reflects the strongest signal; treat as
 *    "low certainty either way"
 */
enum class VerdictLabel { GENUINE, SUSPICIOUS, FAKE }

/**
 * One frame of input to the detection pipeline.
 *
 * `rgb` is **packed 8-bit RGB** (3 bytes per pixel, row-major, no padding).
 * If the source format is YUV (CameraX `YUV_420_888`) the camera engine
 * converts in-place into a pre-allocated buffer before calling `analyze()`.
 *
 * Bible Section 9 engineering rule: zero allocations in the per-frame path.
 * The same [FrameInput] instance is reused across frames where possible —
 * callers must NOT hold onto the underlying ByteArray past the analyze() call.
 */
data class FrameInput(
    val rgb: ByteArray,
    val width: Int,
    val height: Int,
    val timestampNs: Long
) {
    // Generated equals/hashCode on a ByteArray-bearing data class is wrong;
    // provide identity-based equality to avoid expensive O(n) comparisons in
    // hot paths.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * The output of [DetectionEngine.analyze].
 *
 * `heatmap` is the Grad-CAM attention grid, flattened row-major. It's
 * optional because we only compute it on tap or on verdict change (bible
 * Section 6 — too expensive to do every frame). When present it's a 7×7
 * or 14×14 grid normalized to [0, 1].
 *
 * `backend` is one of "NPU", "GPU", "CPU", or "MOCK". The UI shows this in
 * the live HUD (stretch 3) and the manifest captures it as evidence of
 * which accelerator produced the verdict.
 */
data class Verdict(
    val label: VerdictLabel,
    val confidence: Float,
    val heatmap: FloatArray?,
    val latencyMs: Int,
    val backend: String,
    val modelId: String,
    /** Raw NPU classifier score in [0, 1]; higher = more likely fake. */
    val npuScore: Float = 0f,
    /** rPPG liveness SNR in [0, 1]; null when unavailable. Bible Section 6. */
    val rppgScore: Float? = null
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)

    companion object {
        /** The mock backend label — used by [io.teamsnapped.pramana.mocks.MockDetectionEngine]. */
        const val BACKEND_MOCK = "MOCK"
        const val BACKEND_NPU  = "NPU"
        const val BACKEND_GPU  = "GPU"
        const val BACKEND_CPU  = "CPU"
    }
}
