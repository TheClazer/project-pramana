package io.teamsnapped.pramana.mocks

import androidx.lifecycle.LifecycleOwner
import io.teamsnapped.pramana.api.CameraEngine
import io.teamsnapped.pramana.api.CaptureMeta
import io.teamsnapped.pramana.api.SealEngine
import io.teamsnapped.pramana.api.SealedFile
import io.teamsnapped.pramana.api.Verdict
import io.teamsnapped.pramana.api.DetectionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import io.teamsnapped.pramana.api.FrameInput

/**
 * Spins a fake analyzer loop at ~30 Hz against a [MockDetectionEngine].
 * Useful when no camera is plugged in (CI, emulator, or just Compose preview).
 */
class MockCameraEngine(
    private val detectionEngine: DetectionEngine = MockDetectionEngine(),
    private val sealEngine: SealEngine = MockSealEngine()
) : CameraEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pump: Job? = null
    private val _verdictBus = MutableSharedFlow<Verdict>(
        replay = 1,
        extraBufferCapacity = 4
    )
    override val verdictBus: SharedFlow<Verdict> = _verdictBus.asSharedFlow()

    private var lastVerdict: Verdict? = null

    override fun start(lifecycleOwner: LifecycleOwner) {
        pump?.cancel()
        pump = scope.launch {
            val fakeFrame = FrameInput(ByteArray(640 * 480 * 3), 640, 480, 0L)
            while (true) {
                val v = detectionEngine.analyze(fakeFrame.copy(timestampNs = System.nanoTime()))
                lastVerdict = v
                _verdictBus.tryEmit(v)
                delay(33L)   // ~30 Hz
            }
        }
    }

    override fun stop() {
        pump?.cancel()
        pump = null
    }

    override fun captureStill(onResult: (SealedFile?) -> Unit) {
        scope.launch {
            // Fake "shutter" — produce a tiny PNG-ish byte sequence and seal it.
            val fakeBytes = ByteArray(1024) { (it % 256).toByte() }
            val v = lastVerdict ?: detectionEngine.analyze(
                FrameInput(ByteArray(640 * 480 * 3), 640, 480, System.nanoTime())
            )
            val meta = CaptureMeta(
                deviceModel = "MockDevice",
                deviceFingerprint = "00".repeat(32),
                capturedAtUnixMs = System.currentTimeMillis(),
                iso = 100, exposureUs = 8333, focalLengthMm = 4.38f
            )
            onResult(sealEngine.seal(fakeBytes, meta, v))
        }
    }

    override fun backend(): String = detectionEngine.backend()

    fun shutdown() = scope.cancel()
}
