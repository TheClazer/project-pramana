package io.teamsnapped.pramana.detection

import android.content.Context
import io.teamsnapped.pramana.api.DetectionEngine
import io.teamsnapped.pramana.api.FrameInput
import io.teamsnapped.pramana.api.RppgStream
import io.teamsnapped.pramana.api.Verdict
import io.teamsnapped.pramana.api.VerdictLabel
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Detection engine wiring:
 *
 *   FrameInput → preprocess (224×224 float32) → TfliteRunner.run() → fusion
 *   with rPPG → Verdict.
 *
 * The rPPG stream is fed by the same RGB frames so both pipelines see the
 * same data. The Fusion layer combines the two into one verdict.
 *
 * Bible Section 6.
 */
class RealDetectionEngine(
    context: Context,
    modelAsset: String,
    private val rppgStream: RppgStream,
    private val modelId: String
) : DetectionEngine {

    private val runner = TfliteRunner(context, modelAsset)
    private val fusion = Fusion()

    // Pre-allocated buffers — bible engineering rule "zero per-frame allocations."
    private val modelInput: ByteBuffer = ByteBuffer
        .allocateDirect(1 * 3 * 224 * 224 * 4)
        .order(ByteOrder.nativeOrder())
    private val modelOutput: Array<FloatArray> = arrayOf(FloatArray(2))

    init {
        runner.init()
    }

    override suspend fun analyze(frame: FrameInput): Verdict {
        rppgStream.pushFrame(frame.rgb, frame.width, frame.height, frame.timestampNs)

        // Preprocess: center-crop, downsize to 224, normalize to [0,1]
        val started = System.nanoTime()
        modelInput.rewind()
        PreprocessIntoTensor.run(frame, modelInput)

        // Run TFLite. If runner is not ready (model not dropped yet) — return
        // a SUSPICIOUS verdict so the camera loop keeps moving.
        if (!runner.isReady()) {
            return Verdict(
                label      = VerdictLabel.SUSPICIOUS,
                confidence = 0.5f,
                heatmap    = null,
                latencyMs  = ((System.nanoTime() - started) / 1_000_000).toInt(),
                backend    = "ERR",
                modelId    = modelId,
                npuScore   = 0.5f,
                rppgScore  = rppgStream.latestScore()
            )
        }
        runner.run(modelInput, modelOutput)
        val raw = modelOutput[0]
        // Softmax-style 2-class: [real, fake]
        val expReal = kotlin.math.exp(raw[0].toDouble())
        val expFake = kotlin.math.exp(raw[1].toDouble())
        val npuScore = (expFake / (expReal + expFake)).toFloat()

        val rppgScore = rppgStream.latestScore()
        val (label, _) = fusion.fuse(npuScore, rppgScore, System.currentTimeMillis())

        val latencyMs = ((System.nanoTime() - started) / 1_000_000).toInt()
        return Verdict(
            label      = label,
            confidence = computeConfidence(label, npuScore, rppgScore),
            heatmap    = null,             // grad-cam computed on tap, not every frame
            latencyMs  = latencyMs,
            backend    = runner.backend(),
            modelId    = modelId,
            npuScore   = npuScore,
            rppgScore  = rppgScore
        )
    }

    private fun computeConfidence(label: VerdictLabel, npu: Float, rppg: Float?): Float {
        // For FAKE: how strongly we believe it's fake.
        // For GENUINE: how strongly we believe it's real.
        // For SUSPICIOUS: low certainty either way → middle-ish.
        return when (label) {
            VerdictLabel.FAKE       -> npu.coerceIn(0f, 1f)
            VerdictLabel.GENUINE    -> {
                val classifierConf = 1f - npu
                if (rppg != null) (0.6f * classifierConf + 0.4f * rppg).coerceIn(0f, 1f)
                else classifierConf.coerceIn(0f, 1f)
            }
            VerdictLabel.SUSPICIOUS -> 0.5f
        }
    }

    override fun isReady() = runner.isReady()
    override fun backend(): String = runner.backend()
    override fun close() = runner.close()
}
