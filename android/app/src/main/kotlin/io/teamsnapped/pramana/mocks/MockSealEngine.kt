package io.teamsnapped.pramana.mocks

import io.teamsnapped.pramana.api.CaptureMeta
import io.teamsnapped.pramana.api.DetectionMeta
import io.teamsnapped.pramana.api.Manifest
import io.teamsnapped.pramana.api.SealEngine
import io.teamsnapped.pramana.api.SealedFile
import io.teamsnapped.pramana.api.SensorMeta
import io.teamsnapped.pramana.api.Verdict
import java.security.MessageDigest
import java.util.Base64

/**
 * Pure-Kotlin mock — no Keystore, no EXIF embedding, no DCT. Useful for
 * UI development and for the CLI tools/cli_sealverify Python tester.
 *
 * Real seal happens via [io.teamsnapped.pramana.seal.RealSealEngine] when
 * Engineer C's modules are wired up.
 */
class MockSealEngine : SealEngine {

    private val fakePublicKey: ByteArray = ByteArray(32).also { it.fill(0x42.toByte()) }

    override suspend fun seal(
        bytes: ByteArray,
        captureMeta: CaptureMeta,
        detection: Verdict
    ): SealedFile {
        val contentHash = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }

        val keyId = MessageDigest.getInstance("SHA-256")
            .digest(fakePublicKey).joinToString("") { "%02x".format(it) }

        val manifest = Manifest(
            capturedAt        = captureMeta.capturedAtUnixMs.toString(),
            deviceFingerprint = captureMeta.deviceFingerprint,
            sensor = SensorMeta(
                model        = captureMeta.deviceModel,
                iso          = captureMeta.iso.toString(),
                exposureUs   = captureMeta.exposureUs.toString(),
                focalLengthMm = captureMeta.focalLengthMm.toString()
            ),
            contentHash = contentHash,
            detection = DetectionMeta(
                score   = detection.npuScore.toString(),
                label   = detection.label.name,
                model   = detection.modelId,
                backend = detection.backend
            ),
            keyId     = keyId,
            signature = "MOCK_SIGNATURE_NOT_VERIFIABLE"
        )

        // Mock just returns the original bytes — UI can still show the
        // "Sealed!" affordance using the manifest, which is what we want for
        // dev-time mocking.
        return SealedFile(bytes = bytes, manifest = manifest, sigOk = false)
    }

    override fun isReady() = true
    override fun publicKeyB64(): String =
        Base64.getEncoder().encodeToString(fakePublicKey)
}
