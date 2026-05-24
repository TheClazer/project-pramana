package io.teamsnapped.pramana.dct

/**
 * RGB ⇄ YCbCr helpers using the BT.601 (JPEG) matrix.
 *
 * Bible Section 7 — the DCT watermark lives in the Y (luminance) channel
 * only, because chroma is subsampled by every JPEG encoder and the
 * watermark would be quantized to oblivion in Cb/Cr.
 *
 * We use Float intermediates so the watermark deltas stay precise across
 * the round-trip; on the read side we round to int after IDCT.
 */
internal object YCbCr {

    /**
     * RGB (3 bytes per pixel, packed, row-major) → three Float arrays
     * `[Y, Cb, Cr]` of width*height each.
     */
    fun fromRgb(rgb: ByteArray, width: Int, height: Int): Array<FloatArray> {
        val n = width * height
        val y  = FloatArray(n); val cb = FloatArray(n); val cr = FloatArray(n)
        var i = 0; var j = 0
        while (i < rgb.size) {
            val r = (rgb[i].toInt() and 0xff).toFloat()
            val g = (rgb[i + 1].toInt() and 0xff).toFloat()
            val b = (rgb[i + 2].toInt() and 0xff).toFloat()
            y[j]  =  0.299f * r + 0.587f * g + 0.114f * b
            cb[j] = -0.168736f * r - 0.331264f * g + 0.5f * b + 128f
            cr[j] =  0.5f * r - 0.418688f * g - 0.081312f * b + 128f
            i += 3; j++
        }
        return arrayOf(y, cb, cr)
    }

    /** Inverse — writes packed RGB into `out` (must be 3*width*height bytes). */
    fun toRgb(y: FloatArray, cb: FloatArray, cr: FloatArray, out: ByteArray) {
        var i = 0
        for (j in y.indices) {
            val yj = y[j]
            val cbj = cb[j] - 128f
            val crj = cr[j] - 128f
            val r = (yj + 1.402f * crj).coerceIn(0f, 255f)
            val g = (yj - 0.344136f * cbj - 0.714136f * crj).coerceIn(0f, 255f)
            val b = (yj + 1.772f * cbj).coerceIn(0f, 255f)
            out[i]     = r.toInt().toByte()
            out[i + 1] = g.toInt().toByte()
            out[i + 2] = b.toInt().toByte()
            i += 3
        }
    }
}
