package io.teamsnapped.pramana.detection

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Thin TFLite interpreter wrapper that owns the QNN→GPU→CPU fallback ladder.
 *
 * Bible Section 6 (Engineer A) + Section 13 (QNN init flagged MANUAL).
 *
 * **TODO(human):** when the QNN TFLite Delegate AAR is dropped into
 * `app/libs/qnn-tflite-delegate.aar` and the Gradle line is uncommented,
 * complete `tryQnn()` with the correct delegate-init incantation from
 * Qualcomm's `ai-hub-apps` sample. The exact options object name and
 * builder ordering changes per QNN SDK version — vibe-coding it produces a
 * silently-broken delegate that runs on CPU but reports "NPU." Reference:
 * https://github.com/quic/ai-hub-models / ai-hub-apps Android sample.
 *
 * The GPU and CPU paths below are complete and runnable today.
 */
class TfliteRunner(
    private val context: Context,
    /** Filename in assets, e.g. "pramana-int8.tflite". */
    private val modelAsset: String
) {

    private val tag = "Pramana.Tflite"

    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var backendLabel: String = "INIT"

    fun backend(): String = backendLabel
    fun isReady(): Boolean = interpreter != null

    /** Try delegate tiers in order. Returns the backend label we landed on. */
    fun init(): String {
        val modelBuffer = loadModel() ?: run {
            backendLabel = "ERR"
            Log.e(tag, "Could not load model asset $modelAsset")
            return backendLabel
        }

        // Tier 1 — QNN HTP
        tryQnn(modelBuffer)?.let { interp ->
            interpreter = interp; backendLabel = "NPU"; return backendLabel
        }
        // Tier 2 — GPU delegate
        tryGpu(modelBuffer)?.let { interp ->
            interpreter = interp; backendLabel = "GPU"; return backendLabel
        }
        // Tier 3 — XNNPACK CPU
        tryCpu(modelBuffer)?.let { interp ->
            interpreter = interp; backendLabel = "CPU"; return backendLabel
        }
        backendLabel = "ERR"
        return backendLabel
    }

    fun run(input: ByteBuffer, output: Array<FloatArray>) {
        val it = interpreter ?: error("interpreter not initialized")
        it.run(input, output)
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }

    // ------------------------------------------------------------------ //
    //  Delegate attempts
    // ------------------------------------------------------------------ //

    private fun tryQnn(model: ByteBuffer): Interpreter? {
        return try {
            // TODO(human): replace this stub with the real QNN delegate init
            // from the ai-hub-apps sample. The class is something like
            //   com.qualcomm.qti.qnn.tflite.QnnTfLiteDelegate
            // and the options builder takes target ("htp"/"gpu"/"cpu") and
            // backend type. Until then, we explicitly return null so the
            // ladder advances to GPU.
            Log.i(tag, "QNN delegate path is stubbed — see TODO in TfliteRunner.tryQnn")
            null
        } catch (t: Throwable) {
            Log.w(tag, "QNN delegate failed: ${t.message}")
            null
        }
    }

    private fun tryGpu(model: ByteBuffer): Interpreter? {
        return try {
            val compat = CompatibilityList()
            if (!compat.isDelegateSupportedOnThisDevice) {
                Log.i(tag, "GPU delegate not supported on this device")
                return null
            }
            val opts = Interpreter.Options().apply {
                addDelegate(GpuDelegate(compat.bestOptionsForThisDevice))
            }
            Interpreter(model, opts).also { warmUp(it) }
        } catch (t: Throwable) {
            Log.w(tag, "GPU delegate failed: ${t.message}")
            null
        }
    }

    private fun tryCpu(model: ByteBuffer): Interpreter? {
        return try {
            val opts = Interpreter.Options().apply {
                numThreads = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
                setUseXNNPACK(true)
            }
            Interpreter(model, opts).also { warmUp(it) }
        } catch (t: Throwable) {
            Log.w(tag, "CPU delegate failed: ${t.message}")
            null
        }
    }

    private fun warmUp(interp: Interpreter) {
        // 224x224 RGB float32 = 1*3*224*224*4 bytes
        val n = 1 * 3 * 224 * 224 * 4
        val warm = ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder())
        val out  = arrayOf(FloatArray(2))
        try { interp.run(warm, out) } catch (_: Throwable) { /* shape may differ; not fatal */ }
    }

    private fun loadModel(): ByteBuffer? = try {
        val afd = context.assets.openFd(modelAsset)
        val channel = afd.createInputStream().channel
        channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
    } catch (t: Throwable) {
        Log.w(tag, "Asset $modelAsset not found — Engineer A hasn't dropped the .tflite yet.")
        null
    }
}
