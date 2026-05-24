package io.teamsnapped.pramana.detection

import io.teamsnapped.pramana.api.VerdictLabel

/**
 * Fusion layer — bible Section 6.
 *
 * Combines NPU classifier "manipulation probability" (higher = more likely
 * fake) and rPPG "liveness SNR" (higher = stronger pulse) into a single
 * `authenticity` score and a [VerdictLabel].
 *
 * ```
 * authenticity = 0.65 * (1 - npu_score) + 0.35 * rppg_score    # if rPPG
 * authenticity = 1.0  * (1 - npu_score)                        # otherwise
 * if rppg_available && rppg_score < 0.2 for > 2s:
 *     authenticity = min(authenticity, 0.35)                   # HARD VETO
 *
 * label = GENUINE      if authenticity > 0.7
 *         SUSPICIOUS   if authenticity > 0.3
 *         FAKE         otherwise
 * ```
 *
 * The "hard-veto persistence" needs a 2-second history; this object is
 * stateful for that reason. Build one instance per camera session.
 */
class Fusion(
    private val targetFps: Float = 30f
) {

    private data class Sample(val tsMs: Long, val rppg: Float)
    private val rppgHistory = ArrayDeque<Sample>()
    private val HARD_VETO_WINDOW_MS = 2_000L
    private val LOW_RPPG_THRESHOLD = 0.2f

    /**
     * @param npuScore    classifier output [0..1]; higher = more fake
     * @param rppgScore   rPPG SNR [0..1] or null if unavailable
     * @param nowMs       wall-clock for the hard-veto window
     * @return            ([VerdictLabel], authenticity score [0..1])
     */
    fun fuse(npuScore: Float, rppgScore: Float?, nowMs: Long): Pair<VerdictLabel, Float> {
        val rppgAvailable = rppgScore != null
        var authenticity = if (rppgAvailable) {
            0.65f * (1f - npuScore) + 0.35f * rppgScore!!
        } else {
            (1f - npuScore)
        }.coerceIn(0f, 1f)

        if (rppgAvailable) {
            rppgHistory.addLast(Sample(nowMs, rppgScore!!))
            while (rppgHistory.isNotEmpty() && rppgHistory.first().tsMs < nowMs - HARD_VETO_WINDOW_MS) {
                rppgHistory.removeFirst()
            }
            val recent = rppgHistory.toList()
            val spanMs = if (recent.isNotEmpty()) nowMs - recent.first().tsMs else 0L
            val allLow  = recent.all { it.rppg < LOW_RPPG_THRESHOLD }
            if (spanMs >= HARD_VETO_WINDOW_MS && allLow) {
                authenticity = minOf(authenticity, 0.35f)
            }
        } else {
            // rPPG unavailable — reset history so we don't carry stale data
            // across availability transitions.
            rppgHistory.clear()
        }

        val label = when {
            authenticity > 0.7f -> VerdictLabel.GENUINE
            authenticity > 0.3f -> VerdictLabel.SUSPICIOUS
            else                -> VerdictLabel.FAKE
        }
        return label to authenticity
    }
}
