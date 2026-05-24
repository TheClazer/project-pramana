package io.teamsnapped.pramana.mocks

import io.teamsnapped.pramana.api.DetectionMeta
import io.teamsnapped.pramana.api.Manifest
import io.teamsnapped.pramana.api.SensorMeta
import io.teamsnapped.pramana.api.Verdict
import io.teamsnapped.pramana.api.VerdictLabel
import io.teamsnapped.pramana.api.VerifyEngine
import io.teamsnapped.pramana.api.VerifyResult

/**
 * Cycles through every [VerifyResult] state so the UI can be developed
 * against all five badges (green / amber / red / neutral / grey).
 */
class MockVerifyEngine : VerifyEngine {
    private var counter = 0

    override suspend fun verify(bytes: ByteArray, mimeType: String): VerifyResult {
        counter++

        val sampleManifest = Manifest(
            capturedAt        = System.currentTimeMillis().toString(),
            deviceFingerprint = "ab".repeat(32),
            sensor = SensorMeta(
                model = "Pramana-DevDevice",
                iso = "100", exposureUs = "8333", focalLengthMm = "4.38"
            ),
            contentHash = "cd".repeat(32),
            detection = DetectionMeta(
                score = "0.04", label = "GENUINE",
                model = "pramana-mobilenet-v3-small-int8-v1", backend = "NPU"
            ),
            keyId = "ef".repeat(32),
            signature = "MOCK"
        )

        return when (counter % 5) {
            0 -> VerifyResult.VerifiedOriginal(sampleManifest)
            1 -> VerifyResult.VerifiedButModified(
                manifest = sampleManifest,
                expectedContentHash = "cd".repeat(32),
                actualContentHash   = "00".repeat(32)
            )
            2 -> VerifyResult.BrokenSeal(io.teamsnapped.pramana.api.BrokenSealReason.SIGNATURE_INVALID)
            3 -> VerifyResult.NoProvenance(
                Verdict(
                    label = VerdictLabel.FAKE,
                    confidence = 0.92f,
                    heatmap = null,
                    latencyMs = 8,
                    backend = Verdict.BACKEND_MOCK,
                    modelId = "mock-v1",
                    npuScore = 0.91f,
                    rppgScore = null
                )
            )
            else -> VerifyResult.Unreadable
        }
    }
}
