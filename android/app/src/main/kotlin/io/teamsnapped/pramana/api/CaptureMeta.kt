package io.teamsnapped.pramana.api

/**
 * Capture metadata gathered by the camera engine at the moment of shutter.
 *
 * Bible Section 7 — feeds the manifest's `sensor` block. All numeric fields
 * are kept as primitives here but the manifest stringifies them per the
 * v1.1 canonicalization rule (RFC 8785 JCS + all numerics as strings to
 * remove float-formatting risk between Sealer and Verifier).
 *
 * `deviceModel` comes from Build.MODEL; ISO / exposure / focal length come
 * from CameraX CaptureResult when available. When the camera doesn't
 * provide them (e.g. cheap sensors), we substitute 0 / "unknown" — the
 * verifier doesn't reject on missing fields, it just shows fewer details.
 */
data class CaptureMeta(
    val deviceModel: String,
    /** SHA-256 of ANDROID_ID. Bible Section 7. Never the raw ANDROID_ID. */
    val deviceFingerprint: String,
    val capturedAtUnixMs: Long,
    val iso: Int,
    val exposureUs: Int,
    val focalLengthMm: Float
)
