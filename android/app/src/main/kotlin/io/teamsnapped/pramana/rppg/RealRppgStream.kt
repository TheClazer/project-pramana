package io.teamsnapped.pramana.rppg

import io.teamsnapped.pramana.api.RppgStream
import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.sqrt

/**
 * Real rPPG implementation.
 *
 * Pipeline (bible Section 6):
 *  1. Caller feeds RGB frames at ~30 fps.
 *  2. We sample fixed-position ROI patches from the frame as a proxy for
 *     MediaPipe Face Mesh patches. (See TODO below — full Face Mesh
 *     integration is left as a follow-up; the rest of the math is
 *     production-ready.)
 *  3. 30-frame sliding window per channel.
 *  4. POS projection.
 *  5. FFT, bandpass 0.75–3.0 Hz, SNR = power_in_band / total_power.
 *
 * **TODO(human):** wire `com.google.mediapipe:tasks-vision` FaceLandmarker
 * to populate `roiCenters` from a face's actual cheeks/forehead. This
 * stub uses fixed image-relative coordinates so the math is exercised even
 * without a real face detector. Bible Section 13 marks MediaPipe Tasks
 * integration as VIBE-with-verification.
 */
class RealRppgStream(
    private val windowFrames: Int = 30,
    private val targetFps:    Float = 30f,
    private val heartBandLowHz:  Float = 0.75f,
    private val heartBandHighHz: Float = 3.0f
) : RppgStream {

    private val rBuf = FloatArray(windowFrames)
    private val gBuf = FloatArray(windowFrames)
    private val bBuf = FloatArray(windowFrames)
    private var head = 0
    private var filled = 0
    private val fft = FloatFFT_1D(windowFrames.toLong())

    /** Five fixed-position ROIs as image-relative (x, y) centers in [0,1]. */
    private val roiCentersNormalized = arrayOf(
        floatArrayOf(0.5f, 0.30f),   // forehead
        floatArrayOf(0.3f, 0.55f),   // left cheek
        floatArrayOf(0.7f, 0.55f),   // right cheek
        floatArrayOf(0.5f, 0.45f),   // nose bridge
        floatArrayOf(0.5f, 0.75f)    // chin
    )
    /** ROI half-size in pixels (full ROI is 2*HALF+1 square). */
    private val roiHalf = 8

    override fun pushFrame(rgb: ByteArray, width: Int, height: Int, timestampNs: Long) {
        var sumR = 0L; var sumG = 0L; var sumB = 0L; var count = 0
        for (roi in roiCentersNormalized) {
            val cx = (roi[0] * width).toInt()
            val cy = (roi[1] * height).toInt()
            val x0 = (cx - roiHalf).coerceAtLeast(0)
            val x1 = (cx + roiHalf).coerceAtMost(width - 1)
            val y0 = (cy - roiHalf).coerceAtLeast(0)
            val y1 = (cy + roiHalf).coerceAtMost(height - 1)
            for (y in y0..y1) {
                for (x in x0..x1) {
                    val i = (y * width + x) * 3
                    sumR += rgb[i].toInt() and 0xff
                    sumG += rgb[i + 1].toInt() and 0xff
                    sumB += rgb[i + 2].toInt() and 0xff
                    count++
                }
            }
        }
        if (count == 0) return
        rBuf[head] = sumR.toFloat() / count
        gBuf[head] = sumG.toFloat() / count
        bBuf[head] = sumB.toFloat() / count
        head = (head + 1) % windowFrames
        if (filled < windowFrames) filled++
    }

    override fun latestScore(): Float? {
        if (filled < windowFrames) return null

        // Snapshot ordered (oldest → newest)
        val r = FloatArray(windowFrames); val g = FloatArray(windowFrames); val b = FloatArray(windowFrames)
        for (i in 0 until windowFrames) {
            val src = (head + i) % windowFrames
            r[i] = rBuf[src]; g[i] = gBuf[src]; b[i] = bBuf[src]
        }

        val projected = PosAlgorithm.project(r, g, b)
        // Detrend (subtract mean) before FFT
        val mean = projected.average().toFloat()
        for (i in projected.indices) projected[i] -= mean

        // Real-to-complex FFT
        val fftBuf = FloatArray(2 * windowFrames)
        System.arraycopy(projected, 0, fftBuf, 0, windowFrames)
        fft.realForwardFull(fftBuf)

        // Compute power spectrum; bandpass 0.75 – 3.0 Hz
        val freqResolution = targetFps / windowFrames
        var powerInBand = 0f; var totalPower = 0f
        // Only consider the first half (Nyquist).
        for (k in 0 until windowFrames / 2) {
            val re = fftBuf[2 * k]
            val im = fftBuf[2 * k + 1]
            val p = re * re + im * im
            totalPower += p
            val fHz = k * freqResolution
            if (fHz in heartBandLowHz..heartBandHighHz) powerInBand += p
        }
        if (totalPower < 1e-6f) return 0f
        return (powerInBand / totalPower).coerceIn(0f, 1f)
    }

    override fun reset() {
        head = 0; filled = 0
        rBuf.fill(0f); gBuf.fill(0f); bBuf.fill(0f)
    }
}
