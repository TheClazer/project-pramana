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
