package io.teamsnapped.pramana.mocks

import io.teamsnapped.pramana.api.DetectionEngine
import io.teamsnapped.pramana.api.FrameInput
import io.teamsnapped.pramana.api.Verdict
import io.teamsnapped.pramana.api.VerdictLabel
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Bible Appendix A example, slightly extended.
 *
 * Produces a scripted verdict sequence so the UI is testable without a real
 * model. Engineer B uses this from day one; Engineer A swaps in the real
 * TFLite-backed engine when the .tflite drops.
 *
 * The script is intentionally lopsided toward GENUINE so the "tap shutter
 * to seal" UX is testable.
 */
class MockDetectionEngine(
    private val latencyMs: Int = 6,
    private val seed: Long = 0L,
) : DetectionEngine {

    private val rng = Random(seed)
    private var counter = 0

    override suspend fun analyze(frame: FrameInput): Verdict {
        // Simulate the ~6 ms HTP target latency the bible cites.
        delay(latencyMs.toLong())

        counter++
        val phase = counter % 60
        val label = when {
            phase in 0..40  -> VerdictLabel.GENUINE
            phase in 41..52 -> VerdictLabel.SUSPICIOUS
            else            -> VerdictLabel.FAKE
        }

        // 7x7 heatmap with a soft peak that drifts — looks alive on the overlay.
        val heatmap = FloatArray(49)
        val centerX = 3 + (counter % 7) - 3
        val centerY = 3 + ((counter / 3) % 7) - 3
        for (i in 0..6) for (j in 0..6) {
            val dx = (i - centerX).toFloat(); val dy = (j - centerY).toFloat()
            heatmap[i * 7 + j] = (1f - kotlin.math.min(1f, (dx*dx + dy*dy) / 18f)).coerceIn(0f, 1f)
        }

        val npuScore = when (label) {
            VerdictLabel.GENUINE    -> rng.nextFloat() * 0.20f
            VerdictLabel.SUSPICIOUS -> 0.30f + rng.nextFloat() * 0.40f
            VerdictLabel.FAKE       -> 0.70f + rng.nextFloat() * 0.30f
        }
        val rppgScore = if (counter > 30) 0.55f + rng.nextFloat() * 0.4f else null

        return Verdict(
            label      = label,
            confidence = 0.7f + rng.nextFloat() * 0.25f,
            heatmap    = heatmap,
            latencyMs  = latencyMs,
            backend    = Verdict.BACKEND_MOCK,
            modelId    = "mock-v1",
            npuScore   = npuScore,
            rppgScore  = rppgScore
        )
    }

    override fun isReady() = true
    override fun backend() = Verdict.BACKEND_MOCK
    override fun close()   = Unit
}
