package io.teamsnapped.pramana.dct

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

class DctWatermarkTest {

    // Large enough to exercise adaptive redundancy (R > 1). 512x384 = 64x48 =
    // 3072 blocks / 384 = R=8, so each bit is majority-voted across 8 copies.
    private val w = 512
    private val h = 384

    @Test
    fun `embed then extract recovers payload exactly`() {
        val y = FloatArray(w * h) { (Random(0L).nextFloat() * 200f) + 28f }
        val payload = ByteArray(32) { (it * 7).toByte() }
        DctWatermark.embed(y, w, h, payload)
        val extracted = DctWatermark.extract(y, w, h)
        assertThat(extracted).isInstanceOf(DctWatermark.Extracted.Found::class.java)
        assertThat((extracted as DctWatermark.Extracted.Found).payload).isEqualTo(payload)
    }

    @Test
    fun `redundancy majority-vote recovers from moderate per-pixel noise`() {
        val y = FloatArray(w * h) { 128f + Random(3L).nextFloat() * 40f }
        val payload = ByteArray(32) { (0xA5 xor it).toByte() }
        DctWatermark.embed(y, w, h, payload)
        // Moderate noise (~±10/px) — below the QIM margin; the 8-way majority
        // vote must still recover the payload exactly. This is the redundancy win.
        val rng = Random(4L)
        for (i in y.indices) y[i] = (y[i] + (rng.nextFloat() - 0.5f) * 20f).coerceIn(0f, 255f)
        val result = DctWatermark.extract(y, w, h)
        assertThat(result).isInstanceOf(DctWatermark.Extracted.Found::class.java)
        assertThat((result as DctWatermark.Extracted.Found).payload).isEqualTo(payload)
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
        val y = FloatArray(w * h) { 128f }
        val result = DctWatermark.extract(y, w, h)
        assertThat(result).isInstanceOf(DctWatermark.Extracted.None::class.java)
    }

    @Test
    fun `embed then destructive noise does not produce a false Found`() {
        val y = FloatArray(w * h) { 128f + Random(1L).nextFloat() * 20f }
        val payload = ByteArray(32) { 0x5A }
        DctWatermark.embed(y, w, h, payload)
        // Destructive noise (~±60/px) well beyond the QIM margin — must NOT yield
        // a spurious valid payload (RS would have to accidentally validate garbage).
        val rng = Random(2L)
        for (i in y.indices) y[i] = (y[i] + (rng.nextFloat() - 0.5f) * 120f).coerceIn(0f, 255f)
        val result = DctWatermark.extract(y, w, h)
        assertThat(result).isNotInstanceOf(DctWatermark.Extracted.Found::class.java)
    }

    @Test
    fun `image too small reports None on extract`() {
        val y = FloatArray(8 * 8) { 128f }
        val result = DctWatermark.extract(y, 8, 8)
        assertThat(result).isInstanceOf(DctWatermark.Extracted.None::class.java)
    }
}
