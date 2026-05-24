package io.teamsnapped.pramana.jcs

import com.google.common.truth.Truth.assertThat
import io.teamsnapped.pramana.api.DetectionMeta
import io.teamsnapped.pramana.api.Manifest
import io.teamsnapped.pramana.api.SensorMeta
import org.junit.Test

/**
 * Bible §7 + §17: the JCS canonical bytes for a fixed manifest MUST be
 * byte-identical across Sealer and Verifier. Any drift breaks every
 * signature. These vectors get re-asserted by the Python CLI (tools/test_vectors)
 * — keep them in sync.
 */
class JcsTest {

    private val testManifest = Manifest(
        version = "1.0",
        generator = "Pramana/1.0",
        capturedAt = "1718368472103",
        deviceFingerprint = "ab".repeat(32),
        sensor = SensorMeta(
            model = "iQOO Z5",
            iso = "100",
            exposureUs = "8333",
            focalLengthMm = "4.38"
        ),
        contentHash = "cd".repeat(32),
        detection = DetectionMeta(
            score = "0.04",
            label = "GENUINE",
            model = "pramana-mobilenet-v3-small-int8-v1",
            backend = "NPU"
        ),
        keyId = "ef".repeat(32),
        signature = ""
    )

    @Test
    fun `canonicalize is deterministic`() {
        val a = Jcs.canonicalize(testManifest)
        val b = Jcs.canonicalize(testManifest)
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `canonicalize ignores the signature field`() {
        val withSig = testManifest.copy(signature = "AAAA")
        val canonA = Jcs.canonicalize(testManifest)                  // blanks the field
        val canonB = Jcs.canonicalize(withSig)                       // also blanks the field
        assertThat(canonA).isEqualTo(canonB)
    }

    @Test
    fun `canonical bytes are valid UTF-8 JSON`() {
        val bytes = Jcs.canonicalize(testManifest)
        val s = String(bytes, Charsets.UTF_8)
        // JCS output is single-line with sorted keys; basic shape check.
        assertThat(s).startsWith("{")
        assertThat(s).endsWith("}")
        assertThat(s).contains("\"captured_at\":\"1718368472103\"")
        assertThat(s).contains("\"signature\":\"\"")
    }

    @Test
    fun `canonical output keys are sorted lexicographically`() {
        val bytes = Jcs.canonicalize(testManifest)
        val s = String(bytes, Charsets.UTF_8)
        // The top-level keys in alphabetical order. captured_at < content_hash < detection < device_fingerprint < generator < key_id < sensor < signature < version
        val captured  = s.indexOf("\"captured_at\"")
        val content   = s.indexOf("\"content_hash\"")
        val detection = s.indexOf("\"detection\"")
        val device    = s.indexOf("\"device_fingerprint\"")
        val generator = s.indexOf("\"generator\"")
        val keyId     = s.indexOf("\"key_id\"")
        val sensor    = s.indexOf("\"sensor\"")
        val signature = s.indexOf("\"signature\"")
        val version   = s.indexOf("\"version\"")
        assertThat(captured).isLessThan(content)
        assertThat(content).isLessThan(detection)
        assertThat(detection).isLessThan(device)
        assertThat(device).isLessThan(generator)
        assertThat(generator).isLessThan(keyId)
        assertThat(keyId).isLessThan(sensor)
        assertThat(sensor).isLessThan(signature)
        assertThat(signature).isLessThan(version)
    }

    @Test
    fun `nested objects also have sorted keys`() {
        val bytes = Jcs.canonicalize(testManifest)
        val s = String(bytes, Charsets.UTF_8)
        // sensor: exposure_us < focal_length_mm < iso < model
        val sensorStart = s.indexOf("\"sensor\":{")
        val exposure  = s.indexOf("\"exposure_us\"",     sensorStart)
        val focal     = s.indexOf("\"focal_length_mm\"", sensorStart)
        val iso       = s.indexOf("\"iso\"",             sensorStart)
        val model     = s.indexOf("\"model\"",           sensorStart)
        assertThat(exposure).isLessThan(focal)
        assertThat(focal).isLessThan(iso)
        assertThat(iso).isLessThan(model)
    }
}
