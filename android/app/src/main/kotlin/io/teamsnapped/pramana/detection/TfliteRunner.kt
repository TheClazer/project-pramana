package io.teamsnapped.pramana.detection

import android.content.Context
import android.util.Log
import com.qualcomm.qti.QnnDelegate
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Thin TFLite interpreter wrapper that owns the QNN→GPU→CPU fallback ladder.
 *
 * Bible §6 (Engineer A) + §13 (QNN init flagged MANUAL).
 *
 * QNN delegate (the real one — verified against the AAR's classes.jar):
 *   - Class is `com.qualcomm.qti.QnnDelegate` (NOT `...qnn.tflite.QnnTfLiteDelegate`).
 *   - Backend enum constant is `HTP_BACKEND` (NOT `HTP`).
 *   - QnnDelegate implements `org.tensorflow.lite.Delegate`, so it drops straight
 *     into `Interpreter.Options.addDelegate(...)`.
 *
 * Device-agnostic by design (bible: the finale device is an unknown Snapdragon):
 *   - We do NOT pin a Hexagon version. The QNN runtime auto-selects the matching
 *     HTP skel library at load time, so one INT8 .tflite runs on V68 (778G) /
 *     V69 / V73 (8 Gen 2) / V75 (8 Gen 3) / ... PROVIDED the matching
 *     libQnnHtpV{NN}Skel.so is bundled in jniLibs/arm64-v8a/ (team/manual step —
 *     the AAR ships only the delegate .so, not the HTP runtime skels).
 *   - setCacheDir() caches the prepared graph so the second launch (and the live
 *     demo) skips the one-time online-prepare latency.
 *
 * Force-backend: pass [ForceBackend] to [init] to pin a single tier — used for the
 * live NPU↔CPU latency A/B demo and for debugging on the loaner device. Default is
 * the full auto ladder. [backend] always reports the tier ACTUALLY reached, so we
 * never claim NPU when we silently fell back.
 */
class TfliteRunner(
    private val context: Context,
    /** Filename in assets, e.g. "pramana-int8.tflite". */
    private val modelAsset: String
) {

    private val tag = "Pramana.Tflite"

    enum class ForceBackend { AUTO, NPU, GPU, CPU;
        companion object {
            fun parse(s: String?): ForceBackend =
                runCatching { valueOf((s ?: "AUTO").uppercase()) }.getOrDefault(AUTO)
        }
    }

    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var qnnDelegate: QnnDelegate? = null
    @Volatile private var gpuDelegate: GpuDelegate? = null
    @Volatile private var backendLabel: String = "INIT"

    fun backend(): String = backendLabel
    fun isReady(): Boolean = interpreter != null

    /**
     * Try delegate tiers and return the backend label landed on. Re-callable:
     * closes any previous interpreter/delegates first, so it doubles as the
     * "switch backend" entry point for the live latency demo.
     */
    fun init(force: ForceBackend = ForceBackend.AUTO): String {
        close()
        val modelBuffer = loadModel() ?: run {
            backendLabel = "ERR"
            Log.e(tag, "Could not load model asset $modelAsset")
            return backendLabel
        }

        val tryNpu = force == ForceBackend.AUTO || force == ForceBackend.NPU
        val tryGpuTier = force == ForceBackend.AUTO || force == ForceBackend.GPU
        val tryCpuTier = force == ForceBackend.AUTO || force == ForceBackend.CPU

        if (tryNpu) tryQnn(modelBuffer)?.let { interp ->
            interpreter = interp; backendLabel = "NPU"; return backendLabel
        }
        if (tryGpuTier) tryGpu(modelBuffer)?.let { interp ->
            interpreter = interp; backendLabel = "GPU"; return backendLabel
        }
        if (tryCpuTier) tryCpu(modelBuffer)?.let { interp ->
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
        interpreter?.close(); interpreter = null
        runCatching { qnnDelegate?.close() }; qnnDelegate = null
        runCatching { gpuDelegate?.close() }; gpuDelegate = null
    }

    // ------------------------------------------------------------------ //
    //  Delegate attempts
    // ------------------------------------------------------------------ //

    private fun tryQnn(model: ByteBuffer): Interpreter? {
        return try {
            Log.i(tag, "Attempting QNN HTP delegate (auto Hexagon-arch select)…")
            val options = QnnDelegate.Options().apply {
                // HTP = Hexagon Tensor Processor (the NPU). No skel version pinned —
                // the runtime picks the one matching this device's Hexagon arch.
                setBackendType(QnnDelegate.Options.BackendType.HTP_BACKEND)
                setHtpPerformanceMode(QnnDelegate.Options.HtpPerformanceMode.HTP_PERFORMANCE_BURST)
                // Cache the prepared graph so re-launch / the live demo is instant.
                runCatching {
                    val dir = java.io.File(context.cacheDir, "qnn_cache").apply { mkdirs() }
                    setCacheDir(dir.absolutePath)
                    setModelToken(modelAsset)
                }
            }
            val delegate = QnnDelegate(options)   // throws UnsupportedOperationException if HTP absent
            qnnDelegate = delegate
            val opts = Interpreter.Options().apply { addDelegate(delegate) }
            Log.i(tag, "QNN HTP delegate initialized.")
            Interpreter(model, opts).also { warmUp(it) }
        } catch (t: Throwable) {
            // Most common cause on an un-provisioned device: the HTP skel lib for
            // this Hexagon version isn't in jniLibs. Fall back honestly to GPU/CPU.
            Log.w(tag, "QNN HTP unavailable (${t.message}); falling back.")
            runCatching { qnnDelegate?.close() }; qnnDelegate = null
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
            val delegate = GpuDelegate(compat.bestOptionsForThisDevice)
            gpuDelegate = delegate
            val opts = Interpreter.Options().apply { addDelegate(delegate) }
            Interpreter(model, opts).also { warmUp(it) }
        } catch (t: Throwable) {
            Log.w(tag, "GPU delegate failed: ${t.message}")
            runCatching { gpuDelegate?.close() }; gpuDelegate = null
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
        // 224x224 RGB float32 = 1*3*224*224*4 bytes. Also primes the QNN graph
        // cache so the first real inference on stage isn't the slow prepare pass.
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
