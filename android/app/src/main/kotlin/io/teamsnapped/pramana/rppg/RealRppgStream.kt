package io.teamsnapped.pramana.rppg

import android.content.Context
import android.util.Log
import io.teamsnapped.pramana.api.RppgStream
import org.jtransforms.fft.FloatFFT_1D
import java.nio.ByteBuffer
import kotlin.math.sqrt
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker.FaceLandmarkerOptions
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.framework.image.ByteBufferImageBuilder

/**
 * Real rPPG implementation.
 *
 * Pipeline (bible Section 6):
 *  1. Caller feeds RGB frames at ~30 fps.
 *  2. We sample fixed-position ROI patches from the frame as a proxy for
 *     MediaPipe Face Mesh patches. If the Face Mesh task file is present in
 *     assets, we dynamically track facial landmarks in real-time.
 *  3. 30-frame sliding window per channel.
 *  4. POS projection.
 *  5. FFT, bandpass 0.75–3.0 Hz, SNR = power_in_band / total_power.
 */
class RealRppgStream(
    private val context: Context? = null,
    private val windowFrames: Int = 30,
    private val targetFps:    Float = 30f,
    private val heartBandLowHz:  Float = 0.75f,
    private val heartBandHighHz: Float = 3.0f
) : RppgStream {

    private val tag = "Pramana.rPPG"

    private val rBuf = FloatArray(windowFrames)
    private val gBuf = FloatArray(windowFrames)
    private val bBuf = FloatArray(windowFrames)
    private var head = 0
    private var filled = 0
    private val fft = FloatFFT_1D(windowFrames.toLong())
    private var landmarker: FaceLandmarker? = null

    init {
        if (context != null) {
            try {
                val hasAsset = try {
                    context.assets.open("face_landmarker.task").use { true }
                } catch (_: Throwable) {
                    false
                }
                if (hasAsset) {
                    Log.i(tag, "Found face_landmarker.task in assets. Initializing MediaPipe FaceLandmarker...")
                    val baseOptions = BaseOptions.builder()
                        .setModelAssetPath("face_landmarker.task")
                        .build()
                    val options = FaceLandmarkerOptions.builder()
                        .setBaseOptions(baseOptions)
                        .setMinFaceDetectionConfidence(0.5f)
                        .setMinTrackingConfidence(0.5f)
                        .setRunningMode(RunningMode.IMAGE)
                        .build()
                    landmarker = FaceLandmarker.createFromOptions(context, options)
                    Log.i(tag, "MediaPipe FaceLandmarker initialized successfully!")
                } else {
                    Log.i(tag, "face_landmarker.task not found in assets. Falling back to fixed ROIs.")
                }
            } catch (t: Throwable) {
                Log.w(tag, "Could not initialize FaceLandmarker: ${t.message}. Using fixed ROIs.")
            }
        }
    }

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
        val lm = landmarker
        if (lm != null) {
            try {
                // Wrap the RGB ByteArray in direct ByteBuffer
                val buffer = ByteBuffer.allocateDirect(rgb.size)
                buffer.put(rgb)
                buffer.rewind()
                
                val mpImage = ByteBufferImageBuilder(buffer, width, height, MPImage.IMAGE_FORMAT_RGB).build()
                try {
                    val result = lm.detect(mpImage)
                    if (result != null && result.faceLandmarks().isNotEmpty()) {
                        val landmarks = result.faceLandmarks()[0]
                        if (landmarks.size > 346) {
                            // Update our normalized ROIs dynamically based on standard Face Mesh cheek/forehead landmark indexes:
                            // Forehead (index 10)
                            // Left cheek (index 117)
                            // Right cheek (index 346)
                            // Nose bridge (index 168)
                            // Chin (index 152)
                            val forehead = landmarks[10]
                            val leftCheek = landmarks[117]
                            val rightCheek = landmarks[346]
                            val nose = landmarks[168]
                            val chin = landmarks[152]
                            
                            roiCentersNormalized[0][0] = forehead.x()
                            roiCentersNormalized[0][1] = forehead.y()
                            
                            roiCentersNormalized[1][0] = leftCheek.x()
                            roiCentersNormalized[1][1] = leftCheek.y()
                            
                            roiCentersNormalized[2][0] = rightCheek.x()
                            roiCentersNormalized[2][1] = rightCheek.y()
                            
                            roiCentersNormalized[3][0] = nose.x()
                            roiCentersNormalized[3][1] = nose.y()
                            
                            roiCentersNormalized[4][0] = chin.x()
                            roiCentersNormalized[4][1] = chin.y()
                        }
                    }
                } finally {
                    mpImage.close()
                }
            } catch (t: Throwable) {
                // Gracefully ignore frame-tracking errors to prevent camera crash
                Log.w(tag, "MediaPipe face tracking frame error: ${t.message}")
            }
        }

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
