package io.teamsnapped.pramana.camera

import android.graphics.ImageFormat
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * YUV_420_888 → packed RGB conversion.
 *
 * Bible Section 9 v1.1: camera output is YUV_420_888 (NOT RGBA_8888) and we
 * pre-allocate the RGB byte array once at startup and reuse it on every
 * frame. **Do NOT allocate inside this function.**
 *
 * The math:
 *   Y'  = (yByte & 0xff)
 *   U'  = (uByte & 0xff) - 128
 *   V'  = (vByte & 0xff) - 128
 *   R = Y' + 1.402  * V'
 *   G = Y' - 0.344  * U' - 0.714 * V'
 *   B = Y' + 1.772  * U'
 *
 * Chroma is subsampled 4:2:0 — every 2×2 luma block shares one (U,V).
 *
 * CameraX guarantees plane[0]=Y, plane[1]=U, plane[2]=V on YUV_420_888.
 * Strides can be != width, so we honor `rowStride` and `pixelStride`.
 */
internal object YuvToRgb {

    /**
     * @param image     ImageProxy with format YUV_420_888.
     * @param rgbOut    pre-allocated output. Length must be `image.width *
     *                  image.height * 3`. Existing contents are overwritten.
     */
    fun convert(image: ImageProxy, rgbOut: ByteArray) {
        require(image.format == ImageFormat.YUV_420_888) {
            "Expected YUV_420_888, got format ${image.format}"
        }
        val w = image.width; val h = image.height
        require(rgbOut.size == w * h * 3) {
            "rgbOut must be ${w*h*3} bytes (got ${rgbOut.size})"
        }
        val yPlane = image.planes[0]; val uPlane = image.planes[1]; val vPlane = image.planes[2]
        val yBuf: ByteBuffer = yPlane.buffer
        val uBuf: ByteBuffer = uPlane.buffer
        val vBuf: ByteBuffer = vPlane.buffer
        val yStride = yPlane.rowStride; val uStride = uPlane.rowStride; val vStride = vPlane.rowStride
        val uPix = uPlane.pixelStride; val vPix = vPlane.pixelStride

        var di = 0
        for (j in 0 until h) {
            val yRow = j * yStride
            val uvRow = (j / 2) * uStride       // u/v rowStride are same
            for (i in 0 until w) {
                val yIx = yRow + i
                val uvCol = (i / 2)
                val uIx = uvRow + uvCol * uPix
                val vIx = uvRow + uvCol * vPix
                val y = yBuf.get(yIx).toInt() and 0xff
                val u = (uBuf.get(uIx).toInt() and 0xff) - 128
                val v = (vBuf.get(vIx).toInt() and 0xff) - 128
                val r = (y + 1.402f   * v).toInt().coerceIn(0, 255)
                val g = (y - 0.344f   * u - 0.714f * v).toInt().coerceIn(0, 255)
                val b = (y + 1.772f   * u).toInt().coerceIn(0, 255)
                rgbOut[di]     = r.toByte()
                rgbOut[di + 1] = g.toByte()
                rgbOut[di + 2] = b.toByte()
                di += 3
            }
        }
    }
}
