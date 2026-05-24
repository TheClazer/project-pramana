package io.teamsnapped.pramana.seal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.teamsnapped.pramana.api.CaptureMeta
import io.teamsnapped.pramana.api.DetectionMeta
import io.teamsnapped.pramana.api.Manifest
import io.teamsnapped.pramana.api.SealEngine
import io.teamsnapped.pramana.api.SealedFile
import io.teamsnapped.pramana.api.SensorMeta
import io.teamsnapped.pramana.api.Verdict
import io.teamsnapped.pramana.dct.DctWatermark
import io.teamsnapped.pramana.dct.YCbCr
import io.teamsnapped.pramana.jcs.Jcs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * The full sealing orchestrator. Combines:
 *
 *   pixels → DCT watermark → re-encode → contentHash → manifest → JCS →
 *   sign (Keystore) → embed in EXIF UserComment → bytes
 *
 * Bible Section 7. v1.1 scope: images only (JPEG). Video sealing is a
 * stretch goal (MP4 udta box).
 */
class RealSealEngine(
    private val appContext: Context,
    private val jpegQuality: Int = 92
) : SealEngine {

    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val provisioned by lazy { Keystore.ensureProvisioned() }
    private val keyIdHex by lazy { Sha256.hex(provisioned.publicKey.encoded) }

    override suspend fun seal(
        bytes: ByteArray,
        captureMeta: CaptureMeta,
        detection: Verdict
    ): SealedFile {
        // 1) Decode JPEG to RGB pixels.
        val bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("could not decode capture bytes as JPEG")

        // Use ARGB_8888 → strip alpha → packed RGB
        val w = bm.width; val h = bm.height
        // Pad to multiple of 8 — DCT works on 8x8 blocks. Crop the trailing
        // 1-7 pixels rather than re-allocate.
        val wAlign = (w / 8) * 8
        val hAlign = (h / 8) * 8

        val pixelsInt = IntArray(wAlign * hAlign)
        bm.getPixels(pixelsInt, 0, wAlign, 0, 0, wAlign, hAlign)
        bm.recycle()

        val rgb = ByteArray(wAlign * hAlign * 3)
        for (i in pixelsInt.indices) {
            val p = pixelsInt[i]
            rgb[i * 3]     = ((p shr 16) and 0xff).toByte()
            rgb[i * 3 + 1] = ((p shr 8)  and 0xff).toByte()
            rgb[i * 3 + 2] = ( p         and 0xff).toByte()
        }

        // 2) Pre-canonicalize a "skeleton" manifest so we know the
        //    fingerprint for the DCT payload. We do NOT include the
        //    signature yet; the watermark only carries manifest fingerprint
        //    + key fingerprint, neither of which depend on the signature.
        val skeleton = buildManifestSkeleton(captureMeta, detection, contentHashHexPlaceholder = "0".repeat(64))
        val skeletonJcs = Jcs.canonicalize(skeleton)
        val payload = DctWatermark.buildPayload(skeletonJcs, keyIdHex)

        // 3) Embed DCT watermark in Y channel.
        val (y, cb, cr) = YCbCr.fromRgb(rgb, wAlign, hAlign)
        DctWatermark.embed(y, wAlign, hAlign, payload)
        YCbCr.toRgb(y, cb, cr, rgb)

        // 4) Re-encode RGB → JPEG. We do this BEFORE hashing so the
        //    contentHash is computed on the canonical decoded pixels of
        //    the bytes the verifier will see.
        val watermarkedJpeg = encodeRgbToJpeg(rgb, wAlign, hAlign, jpegQuality)

        // 5) Decode the watermarked JPEG to canonical pixels and hash THOSE
        //    — this is the value the verifier recomputes.
        val canonicalPixels = decodeJpegToRgb(watermarkedJpeg, wAlign, hAlign)
        val contentHash = Sha256.hex(canonicalPixels)

        // 6) Build the real manifest (with real contentHash), JCS-canonicalize,
        //    sign with Keystore.
        val unsigned = buildManifestSkeleton(captureMeta, detection, contentHashHexPlaceholder = contentHash)
        val jcs = Jcs.canonicalize(unsigned)
        val signatureBytes = try {
            Keystore.sign(jcs, provisioned.tier)
        } catch (t: Throwable) {
            // Last-resort: write the manifest WITHOUT a valid signature; UI
            // surfaces "sigOk = false". Bible Section 11 fallback ladder.
            ByteArray(0)
        }
        val signed = unsigned.copy(signature = Base64.getEncoder().encodeToString(signatureBytes))

        // 7) Embed signed manifest into EXIF UserComment.
        val finalManifestJcs = Jcs.canonicalize(signed.copy(signature = "")) // canonical bytes again, for embed
        // Note: we embed the *signed* JSON (not the canonical bytes) so the
        // verifier can parse it as plain JSON; the canonicalization is
        // recomputed on the verify side from the parsed object.
        val signedJsonString = json.encodeToString(signed)
        val withExif = Exif.embedUserComment(
            imageBytes = watermarkedJpeg,
            manifestJsonCanonical = signedJsonString.toByteArray(Charsets.UTF_8),
            scratchDir = appContext.cacheDir
        )

        return SealedFile(
            bytes = withExif,
            manifest = signed,
            sigOk = signatureBytes.isNotEmpty()
        )
    }

    override fun isReady(): Boolean = true

    override fun publicKeyB64(): String =
        Base64.getEncoder().encodeToString(provisioned.publicKey.encoded)

    // ------------------------------------------------------------------ //
    //  Internals
    // ------------------------------------------------------------------ //

    private fun buildManifestSkeleton(
        meta: CaptureMeta,
        detection: Verdict,
        contentHashHexPlaceholder: String
    ): Manifest = Manifest(
        capturedAt        = meta.capturedAtUnixMs.toString(),
        deviceFingerprint = meta.deviceFingerprint,
        sensor = SensorMeta(
            model         = meta.deviceModel,
            iso           = meta.iso.toString(),
            exposureUs    = meta.exposureUs.toString(),
            focalLengthMm = meta.focalLengthMm.toString()
        ),
        contentHash = contentHashHexPlaceholder,
        detection = DetectionMeta(
            score   = detection.npuScore.toString(),
            label   = detection.label.name,
            model   = detection.modelId,
            backend = detection.backend
        ),
        keyId     = keyIdHex,
        signature = ""
    )

    private fun encodeRgbToJpeg(rgb: ByteArray, w: Int, h: Int, quality: Int): ByteArray {
        // Re-pack RGB → ARGB_8888 → Bitmap → compress
        val pixelsInt = IntArray(w * h)
        for (i in pixelsInt.indices) {
            val r = rgb[i * 3].toInt() and 0xff
            val g = rgb[i * 3 + 1].toInt() and 0xff
            val b = rgb[i * 3 + 2].toInt() and 0xff
            pixelsInt[i] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bm = Bitmap.createBitmap(pixelsInt, w, h, Bitmap.Config.ARGB_8888)
        val bos = ByteArrayOutputStream()
        bm.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        bm.recycle()
        return bos.toByteArray()
    }

    private fun decodeJpegToRgb(jpeg: ByteArray, expectedW: Int, expectedH: Int): ByteArray {
        val bm = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            ?: error("could not decode jpeg")
        val pixelsInt = IntArray(expectedW * expectedH)
        bm.getPixels(pixelsInt, 0, expectedW, 0, 0, expectedW, expectedH)
        bm.recycle()
        val rgb = ByteArray(expectedW * expectedH * 3)
        for (i in pixelsInt.indices) {
            val p = pixelsInt[i]
            rgb[i * 3]     = ((p shr 16) and 0xff).toByte()
            rgb[i * 3 + 1] = ((p shr 8)  and 0xff).toByte()
            rgb[i * 3 + 2] = ( p         and 0xff).toByte()
        }
        return rgb
    }
}
