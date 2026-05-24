package io.teamsnapped.pramana.dct

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

class YCbCrTest {

    @Test
    fun `gray pixel round-trips within 1 unit`() {
        val rgb = ByteArray(3 * 16) { 128.toByte() }
        val (y, cb, cr) = YCbCr.fromRgb(rgb, 4, 4)
        val back = ByteArray(rgb.size)
        YCbCr.toRgb(y, cb, cr, back)
        for (i in rgb.indices) {
            val expected = rgb[i].toInt() and 0xff
            val actual = back[i].toInt() and 0xff
            assertThat(abs(expected - actual)).isLessThan(2)   // BT.601 round-trip ~1 unit
        }
    }

    @Test
    fun `pure red projects to expected Y`() {
        // Y = 0.299*R = 0.299*255 ≈ 76.245
        val rgb = byteArrayOf(0xff.toByte(), 0x00, 0x00)
        val (y, _, _) = YCbCr.fromRgb(rgb, 1, 1)
        assertThat(y[0]).isWithin(0.5f).of(0.299f * 255f)
    }

    @Test
    fun `random rgb round-trips within tolerance`() {
        val rgb = ByteArray(3 * 256) { ((it * 37) % 256).toByte() }
        val (y, cb, cr) = YCbCr.fromRgb(rgb, 16, 16)
        val back = ByteArray(rgb.size)
        YCbCr.toRgb(y, cb, cr, back)
        var maxErr = 0
        for (i in rgb.indices) {
            val e = abs((rgb[i].toInt() and 0xff) - (back[i].toInt() and 0xff))
            if (e > maxErr) maxErr = e
        }
        // BT.601 reversible to within ~2 units due to coefficient rounding.
        assertThat(maxErr).isAtMost(2)
    }
}
