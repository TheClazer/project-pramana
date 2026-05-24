package io.teamsnapped.pramana.api

import com.google.common.truth.Truth.assertThat
import io.teamsnapped.pramana.jcs.Jcs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

class ManifestSerializationTest {

    private val json = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }

    private fun sample() = Manifest(
        capturedAt = "1700000000000",
        deviceFingerprint = "deadbeef".repeat(8),
        sensor = SensorMeta(
            model = "iQOO Z5",
            iso = "100",
            exposureUs = "10000",
            focalLengthMm = "4.0"
        ),
        contentHash = "ffeedd".repeat(11) + "ff",   // 66 hex chars trimmed below
        detection = DetectionMeta(
            score = "0.10",
            label = "GENUINE",
            model = "pramana-mobilenet-v3-small-int8-v1",
            backend = "NPU"
        ),
        keyId = "0badc0de".repeat(8),
        signature = "QkFE"   // "BAD" base64
    )

    @Test
    fun `json round trips`() {
        val m = sample()
        val s = json.encodeToString(m)
        val back = json.decodeFromString(Manifest.serializer(), s)
        assertThat(back).isEqualTo(m)
    }

    @Test
    fun `serial names match bible schema`() {
        val s = json.encodeToString(sample())
        assertThat(s).contains("\"captured_at\"")
        assertThat(s).contains("\"device_fingerprint\"")
        assertThat(s).contains("\"content_hash\"")
        assertThat(s).contains("\"key_id\"")
        assertThat(s).contains("\"focal_length_mm\"")
        assertThat(s).contains("\"exposure_us\"")
    }

    @Test
    fun `canonical bytes are deterministic across instances`() {
        val a = Jcs.canonicalize(sample())
        val b = Jcs.canonicalize(sample())
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `numeric fields are stored as strings per v1_1`() {
        val s = json.encodeToString(sample())
        // ISO field MUST be quoted string "100", not bare number 100.
        assertThat(s).contains("\"iso\":\"100\"")
        assertThat(s).contains("\"exposure_us\":\"10000\"")
        assertThat(s).contains("\"focal_length_mm\":\"4.0\"")
        assertThat(s).contains("\"score\":\"0.10\"")
    }
}
