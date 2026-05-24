package io.teamsnapped.pramana.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Pramāṇa manifest — the cryptographic provenance record embedded in EXIF
 * `UserComment` (tag 0x9286) on every sealed image.
 *
 * **v1.1 canonicalization rule:** the signed bytes are RFC 8785 JCS over the
 * fields below with `signature` excluded. ALL NUMERIC FIELDS ARE STORED AS
 * STRINGS — bible Section 7, "to remove any remaining float-formatting risk."
 * That is why `iso`, `exposureUs`, etc. are typed `String` here.
 *
 * Do NOT change field names without bumping `version`. Both Sealer and
 * Verifier produce the canonical bytes from this exact schema; any drift
 * silently breaks every signature.
 *
 * Bible Section 7 manifest schema.
 */
@Serializable
data class Manifest(
    val version:           String       = "1.0",
    val generator:         String       = "Pramana/1.0",

    @SerialName("captured_at")
    val capturedAt:        String,            // unix ms as string

    @SerialName("device_fingerprint")
    val deviceFingerprint: String,            // SHA-256 hex of ANDROID_ID

    val sensor:            SensorMeta,

    @SerialName("content_hash")
    val contentHash:       String,            // SHA-256 hex of pixel bytes

    val detection:         DetectionMeta,

    @SerialName("key_id")
    val keyId:             String,            // SHA-256 hex of public key

    /** Base64 ECDSA-SHA256 (or Ed25519) signature over the JCS bytes of all other fields. */
    val signature:         String = ""
)

@Serializable
data class SensorMeta(
    val model:                String,
    val iso:                  String,         // stringified per v1.1
    @SerialName("exposure_us")
    val exposureUs:           String,         // stringified per v1.1
    @SerialName("focal_length_mm")
    val focalLengthMm:        String          // stringified per v1.1
)

@Serializable
data class DetectionMeta(
    val score:   String,                       // stringified per v1.1
    val label:   String,                       // GENUINE | SUSPICIOUS | FAKE
    val model:   String,                       // 'pramana-mobilenet-v3-small-int8-v1' etc.
    val backend: String                        // NPU | GPU | CPU | MOCK
)
