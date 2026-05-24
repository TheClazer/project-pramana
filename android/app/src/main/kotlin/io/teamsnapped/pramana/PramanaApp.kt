package io.teamsnapped.pramana

import android.app.Application
import android.util.Log
import io.teamsnapped.pramana.api.CameraEngine
import io.teamsnapped.pramana.api.DetectionEngine
import io.teamsnapped.pramana.api.RppgStream
import io.teamsnapped.pramana.api.SealEngine
import io.teamsnapped.pramana.api.VerifyEngine
import io.teamsnapped.pramana.camera.RealCameraEngine
import io.teamsnapped.pramana.detection.RealDetectionEngine
import io.teamsnapped.pramana.mocks.MockCameraEngine
import io.teamsnapped.pramana.mocks.MockDetectionEngine
import io.teamsnapped.pramana.mocks.MockRppgStream
import io.teamsnapped.pramana.mocks.MockSealEngine
import io.teamsnapped.pramana.mocks.MockVerifyEngine
import io.teamsnapped.pramana.rppg.RealRppgStream
import io.teamsnapped.pramana.seal.Keystore
import io.teamsnapped.pramana.seal.RealSealEngine
import io.teamsnapped.pramana.seal.Sha256
import io.teamsnapped.pramana.seal.TrustStore
import io.teamsnapped.pramana.verify.RealVerifyEngine

/**
 * Minimal DI container — no Hilt/Koin to keep build times down for the
 * hackathon. Each engine has a real impl and a mock; we choose based on
 * whether the corresponding native dependencies are available.
 *
 *  - DetectionEngine: real if the .tflite is in assets, otherwise mock.
 *  - SealEngine:      real always (Keystore works on every modern Android).
 *  - VerifyEngine:    real always.
 *  - CameraEngine:    real always (CameraX); mock available for emulator dev.
 *  - RppgStream:      real always.
 */
class PramanaApp : Application() {

    private val tag = "Pramana.App"

    lateinit var rppgStream:       RppgStream         private set
    lateinit var detectionEngine:  DetectionEngine    private set
    lateinit var sealEngine:       SealEngine         private set
    lateinit var verifyEngine:     VerifyEngine       private set
    lateinit var trustStore:       TrustStore         private set

    /**
     * Built on first request. CameraScreen wires the live PreviewView in
     * later via [io.teamsnapped.pramana.api.CameraEngine.attachPreview].
     */
    fun makeCameraEngine(): CameraEngine {
        return try {
            RealCameraEngine(
                context = this,
                detectionEngine = detectionEngine,
                sealEngine = sealEngine
            )
        } catch (t: Throwable) {
            Log.w(tag, "Real camera bind failed, falling back to mock: ${t.message}")
            MockCameraEngine(detectionEngine, sealEngine)
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Trust store + first-launch keystore seed.
        trustStore = TrustStore(this)
        try {
            val provisioned = Keystore.ensureProvisioned()
            val keyId = Sha256.hex(provisioned.publicKey.encoded)
            val algo = provisioned.publicKey.algorithm
            trustStore.seedSelf(keyId, provisioned.publicKey, algo)
            Log.i(tag, "Keystore provisioned at tier ${provisioned.tier.description}")
        } catch (t: Throwable) {
            Log.e(tag, "Keystore provisioning failed: ${t.message}")
        }

        rppgStream = try { RealRppgStream() } catch (t: Throwable) {
            Log.w(tag, "RealRppgStream failed, using mock: ${t.message}"); MockRppgStream()
        }

        detectionEngine = run {
            val asset = "pramana-int8.tflite"
            // Probe the asset BEFORE building RealDetectionEngine — its TfliteRunner
            // silently degrades to backend="ERR" on a missing asset (always returns
            // SUSPICIOUS), which is a worse demo than the cycling MockDetectionEngine.
            val hasAsset = try {
                assets.openFd(asset).use { true }
            } catch (_: Throwable) {
                false
            }
            if (!hasAsset) {
                Log.i(tag, "No $asset in assets/ — using MockDetectionEngine until you drop a model.")
                MockDetectionEngine()
            } else try {
                RealDetectionEngine(
                    context = this,
                    modelAsset = asset,
                    rppgStream = rppgStream,
                    modelId = BuildConfig.DETECTION_MODEL_ID
                )
            } catch (t: Throwable) {
                Log.w(tag, "RealDetectionEngine failed despite asset present, using mock: ${t.message}")
                MockDetectionEngine()
            }
        }

        sealEngine = try {
            RealSealEngine(this)
        } catch (t: Throwable) {
            Log.w(tag, "RealSealEngine failed, using mock: ${t.message}")
            MockSealEngine()
        }

        verifyEngine = try {
            RealVerifyEngine(this, trustStore, detectionEngine)
        } catch (t: Throwable) {
            Log.w(tag, "RealVerifyEngine failed, using mock: ${t.message}")
            MockVerifyEngine()
        }
    }
}
