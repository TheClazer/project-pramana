// Pramāṇa — FrameInput (bible §17)
//
// Single-frame payload handed to DetectionEngine.analyze(). The byte layout is
// RGB packed (R,G,B,R,G,B...) at width*height pixels — the CameraEngine is
// responsible for YUV→RGB conversion using its pre-allocated buffers. The
// rgb array is BORROWED, NOT OWNED — DetectionEngine MUST NOT retain it
// across calls. If it needs persistence, copy.
//
package io.teamsnapped.pramana.api

/**
 * @param rgb          Packed RGB bytes, length == width * height * 3. Borrowed.
 * @param width        Pixel width of the frame.
 * @param height       Pixel height of the frame.
 * @param timestampNs  Capture timestamp from CameraX.
 * @param faceRoi      Optional pixel-rect of the face crop the analyzer should
 *                     focus on. Null → full-frame analysis. Engineer A's
 *                     reference uses this to skip MediaPipe Face Detection
 *                     when CameraX already provided one.
 */
data class FrameInput(
    val rgb: ByteArray,
    val width: Int,
    val height: Int,
    val timestampNs: Long,
    val faceRoi: PixelRect? = null
) {
    init {
        require(rgb.size == width * height * 3) {
            "rgb size ${rgb.size} != width*height*3 (${width * height * 3})"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FrameInput) return false
        return width == other.width &&
            height == other.height &&
            timestampNs == other.timestampNs &&
            faceRoi == other.faceRoi &&
            rgb.contentEquals(other.rgb)
    }

    override fun hashCode(): Int {
        var r = width
        r = 31 * r + height
        r = 31 * r + timestampNs.hashCode()
        r = 31 * r + (faceRoi?.hashCode() ?: 0)
        r = 31 * r + rgb.contentHashCode()
        return r
    }
}

/** Integer pixel rectangle in source-frame coordinates. */
data class PixelRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
}
