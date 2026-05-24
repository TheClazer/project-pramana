package io.teamsnapped.pramana.camera

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import io.teamsnapped.pramana.api.CameraEngine
import io.teamsnapped.pramana.api.CaptureMeta
import io.teamsnapped.pramana.api.DetectionEngine
import io.teamsnapped.pramana.api.FrameInput
import io.teamsnapped.pramana.api.SealEngine
import io.teamsnapped.pramana.api.SealedFile
import io.teamsnapped.pramana.api.Verdict
import io.teamsnapped.pramana.seal.Sha256
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

/**
 * Real CameraX implementation with:
 *  - YUV_420_888 ImageAnalysis (bible v1.1 rule)
 *  - STRATEGY_KEEP_ONLY_LATEST (drop frames if analyzer is slow)
 *  - Pre-allocated RGB buffer reused on every frame (NO per-frame alloc)
 *  - ImageCapture for shutter
 *  - Verdict bus via SharedFlow (replay=1)
 *
 * Engineer B owns this file. Bible Section 9.
 *
 * The PreviewView is attached via [attachPreview] from the Compose UI
 * (after the AndroidView lays it out). This avoids a chicken-and-egg
 * problem where the engine is constructed before the View exists.
 */
class RealCameraEngine(
    private val context: Context,
    private val detectionEngine: DetectionEngine,
    private val sealEngine: SealEngine,
    private val targetAnalysisSize: Size = Size(640, 480)
) : CameraEngine {

    private val tag = "Pramana.Camera"
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _verdictBus = MutableSharedFlow<Verdict>(replay = 1, extraBufferCapacity = 4)
    override val verdictBus: SharedFlow<Verdict> = _verdictBus.asSharedFlow()

    // Pre-allocated frame buffer — sized for the analysis resolution.
    private val rgbBuffer = ByteArray(targetAnalysisSize.width * targetAnalysisSize.height * 3)
    private val frameInput = FrameInput(
        rgb = rgbBuffer,
        width = targetAnalysisSize.width,
        height = targetAnalysisSize.height,
        timestampNs = 0L
    )

    @Volatile private var imageCapture: ImageCapture? = null
    @Volatile private var lastVerdict: Verdict? = null

    // Live preview surface — wired in from Compose AFTER the engine is
    // constructed. Both fields are volatile so the analyzer thread sees
    // updates without re-binding.
    @Volatile private var previewView: PreviewView? = null
    @Volatile private var activePreview: Preview? = null

    override fun attachPreview(view: Any?) {
        val pv = view as? PreviewView
        previewView = pv
        // If we've already bound the Preview use case, update its surface
        // live — handles screen rotation / re-mount cases where Compose
        // hands us a new PreviewView while analysis is already running.
        activePreview?.setSurfaceProvider(pv?.surfaceProvider)
    }

    override fun start(lifecycleOwner: LifecycleOwner) {
        val futureProvider = ProcessCameraProvider.getInstance(context)
        futureProvider.addListener({
            try {
                val provider = futureProvider.get()
                bind(provider, lifecycleOwner)
            } catch (t: Throwable) {
                Log.e(tag, "Camera bind failed: ${t.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bind(provider: ProcessCameraProvider, owner: LifecycleOwner) {
        provider.unbindAll()

        val preview = Preview.Builder().build().also { p ->
            // Read the latest attached view at bind time; subsequent calls to
            // attachPreview() will hot-swap via the activePreview reference.
            previewView?.let { p.setSurfaceProvider(it.surfaceProvider) }
        }
        activePreview = preview

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setTargetResolution(targetAnalysisSize)
            .build()

        analysis.setAnalyzer(analysisExecutor) { proxy ->
            try {
                YuvToRgb.convert(proxy, rgbBuffer)
                val ts = System.nanoTime()
                val v = runBlocking {
                    detectionEngine.analyze(
                        // We mutate the cached FrameInput's timestamp in-place by
                        // constructing a copy that shares the same rgbBuffer (no
                        // alloc — the data class copy reuses the same array ref).
                        frameInput.copy(timestampNs = ts)
                    )
                }
                lastVerdict = v
                _verdictBus.tryEmit(v)
            } catch (t: Throwable) {
                Log.w(tag, "analyzer failure: ${t.message}")
            } finally {
                proxy.close()
            }
        }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        try {
            provider.bindToLifecycle(owner, selector, preview, analysis, imageCapture)
            Log.i(tag, "CameraX bound; analysis size = $targetAnalysisSize")
        } catch (t: Throwable) {
            Log.e(tag, "bindToLifecycle failed: ${t.message}")
        }
    }

    override fun stop() {
        try {
            ProcessCameraProvider.getInstance(context).get().unbindAll()
        } catch (t: Throwable) {
            Log.w(tag, "stop() unbindAll failed: ${t.message}")
        }
        activePreview = null
        imageCapture = null
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancelChildren()
    }

    override fun captureStill(onResult: (SealedFile?) -> Unit) {
        val capture = imageCapture
        if (capture == null) {
            onResult(null); return
        }
        // We do a synchronous capture-to-bytes by going through OnImageCapturedCallback.
        capture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                        scope.launch {
                            val verdict = lastVerdict ?: detectionEngine.analyze(frameInput)
                            val meta = CaptureMeta(
                                deviceModel = android.os.Build.MODEL,
                                deviceFingerprint = Sha256.hex(android.os.Build.FINGERPRINT.toByteArray()),
                                capturedAtUnixMs = System.currentTimeMillis(),
                                iso = 0, exposureUs = 0, focalLengthMm = 0f
                            )
                            val sealed = sealEngine.seal(bytes, meta, verdict)
                            onResult(sealed)
                        }
                    } catch (t: Throwable) {
                        Log.e(tag, "captureStill failed: ${t.message}")
                        onResult(null)
                    } finally {
                        image.close()
                    }
                }
                override fun onError(exception: androidx.camera.core.ImageCaptureException) {
                    Log.e(tag, "takePicture error", exception)
                    onResult(null)
                }
            }
        )
    }

    override fun backend(): String = detectionEngine.backend()
}
