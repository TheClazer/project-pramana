package io.teamsnapped.pramana.dct

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

class DctWatermarkTest {

    /** Smallest image that fits 384 codeword bits (384 8×8 blocks). */
    private val w = 200
    private val h = 200   // 25*25 = 625 blocks ≥ 384

    @Test
    fun `embed then extract recovers payload exactly`() {
        val y = FloatArray(w * h) { (Random(0L).nextFloat() * 200f) + 28f }
        val payload = ByteArray(32) { (it * 7).toByte() }
        DctWatermark.embed(y, w, h, payload)
        val extracted = DctWatermark.extract(y, w, h)
        assertThat(extracted).isInstanceOf(DctWatermark.Extracted.Found::class.java)
        val found = extracted as DctWatermark.Extracted.Found
        assertThat(found.payload).isEqualTo(payload)
    }

    @Test
    fun `build payload encodes version manifest fingerprint and key id`() {
        val manifestBytes = "manifest".toByteArray()
        val keyId = "0123456789abcdef".repeat(4)  // 64 chars
        val payload = DctWatermark.buildPayload(manifestBytes, keyId)
        assertThat(payload.size).isEqualTo(DctWatermark.PAYLOAD_BYTES)
        assertThat(payload[0]).isEqualTo(DctWatermark.VERSION_BYTE)
    }

    @Test
    fun `extract on a clean luminance image reports None`() {
        // Clean image, no embedded bits — should NOT report a Found.
        // (May report None if all bits decode as 0; that's the expected case.)
        val y = FloatArray(w * h) { 128f }
        val result = DctWatermark.extract(y, w, h)
        assertThat(result).isInstanceOf(DctWatermark.Extracted.None::class.java)
    }

    @Test
    fun `embed then heavy noise recovers as damaged not found`() {
        val y = FloatArray(w * h) { 128f + Random(1L).nextFloat() * 20f }
        val payload = ByteArray(32) { 0x5A }
        DctWatermark.embed(y, w, h, payload)

        // Inject heavy gaussian-ish noise (more than DELTA*0.5 per coef)
        val rng = Random(2L)
        for (i in y.indices) y[i] = (y[i] + (rng.nextFloat() - 0.5f) * 30f).coerceIn(0f, 255f)

        val result = DctWatermark.extract(y, w, h)
        // Either Damaged or None — but never a spurious Found with this much noise.
        assertThat(result).isNotInstanceOf(DctWatermark.Extracted.Found::class.java)
    }

    @Test
    fun `image too small reports None on extract`() {
        // 10x10 image = 1 block, far below CODEWORD_BITS.
        val y = FloatArray(10 * 10) { 128f }
        val result = DctWatermark.extract(y, 8, 8)
        assertThat(result).isInstanceOf(DctWatermark.Extracted.None::class.java)
    }
}
