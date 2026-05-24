package io.teamsnapped.pramana.verify

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import io.teamsnapped.pramana.api.BrokenSealReason
import io.teamsnapped.pramana.api.DetectionEngine
import io.teamsnapped.pramana.api.FrameInput
import io.teamsnapped.pramana.api.Manifest
import io.teamsnapped.pramana.api.VerifyEngine
import io.teamsnapped.pramana.api.VerifyResult
import io.teamsnapped.pramana.dct.DctWatermark
import io.teamsnapped.pramana.dct.YCbCr
import io.teamsnapped.pramana.jcs.Jcs
import io.teamsnapped.pramana.seal.Exif
import io.teamsnapped.pramana.seal.Keystore
import io.teamsnapped.pramana.seal.Sha256
import io.teamsnapped.pramana.seal.TrustStore
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * Bible Section 8 verifier — runs through:
 *
 *   1. Try EXIF UserComment: if present, parse, lookup pubkey, verify sig,
 *      recompute pixel hash.
 *   2. If no EXIF: try DCT watermark. If found, the verifier knows a
 *      Pramāṇa origin claim was made; without a manifest however we report
 *      `WATERMARK_ORPHAN` (the manifest could in future be fetched from a
 *      registry).
 *   3. If neither: fall through to `DetectionEngine` (classifier+rPPG) on
 *      the file → `NoProvenance` with verdict.
 */
class RealVerifyEngine(
    private val context: Context,
    private val trustStore: TrustStore,
    private val detectionFallback: DetectionEngine
) : VerifyEngine {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    override suspend fun verify(bytes: ByteArray, mimeType: String): VerifyResult {
        // EXIF path
        val manifestBytes = Exif.readUserComment(bytes)
        if (manifestBytes != null) {
            val parsed = parseManifest(manifestBytes) ?: return VerifyResult.Unreadable
            return verifyManifest(parsed, bytes)
        }

        // DCT path
        val watermark = scanDct(bytes)
        if (watermark is DctWatermark.Extracted.Found ||
            watermark is DctWatermark.Extracted.Damaged) {
            return VerifyResult.BrokenSeal(BrokenSealReason.WATERMARK_ORPHAN)
        }

        // Detection fallback
        return runDetectionFallback(bytes)
    }

    // ------------------------------------------------------------------ //
    //  Internals
    // ------------------------------------------------------------------ //

    private fun parseManifest(jsonBytes: ByteArray): Manifest? = try {
        json.decodeFromString(Manifest.serializer(), String(jsonBytes, Charsets.UTF_8))
    } catch (t: Throwable) {
        Log.w(TAG, "manifest parse failed: ${t.message}")
        null
    }

    private fun verifyManifest(manifest: Manifest, fileBytes: ByteArray): VerifyResult {
        val pub = trustStore.lookup(manifest.keyId)
            ?: return VerifyResult.BrokenSeal(BrokenSealReason.UNKNOWN_KEY)

        val canonical = Jcs.canonicalize(manifest)
        val sigBytes  = try {
            Base64.getDecoder().decode(manifest.signature)
        } catch (t: Throwable) {
            return VerifyResult.BrokenSeal(BrokenSealReason.SIGNATURE_INVALID)
        }
        val tier = when (pub.algorithm) {
            "RSA" -> Keystore.Tier.TEE_RSA_2048
            else  -> Keystore.Tier.TEE_ECDSA_P256
        }
        val sigOk = try {
            Keystore.verify(pub, canonical, sigBytes, tier)
        } catch (t: Throwable) {
            false
        }
        if (!sigOk) return VerifyResult.BrokenSeal(BrokenSealReason.SIGNATURE_INVALID)

        // Recompute pixel hash
        val pixels = decodeToCanonicalRgb(fileBytes) ?: return VerifyResult.Unreadable
        val recomputed = Sha256.hex(pixels)
        return if (recomputed == manifest.contentHash) {
            VerifyResult.VerifiedOriginal(manifest)
        } else {
            VerifyResult.VerifiedButModified(
                manifest = manifest,
                expectedContentHash = manifest.contentHash,
                actualContentHash   = recomputed
            )
        }
    }

    private fun scanDct(bytes: ByteArray): DctWatermark.Extracted {
        val pixels = decodeToCanonicalRgb(bytes) ?: return DctWatermark.Extracted.None
        // We need w/h aligned to 8. decodeToCanonicalRgb already gives that
        // because BitmapFactory.decodeByteArray returns natural dims; we
        // mirror the sealer's crop here.
        val bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return DctWatermark.Extracted.None
        val w = (bm.width / 8) * 8
        val h = (bm.height / 8) * 8
        bm.recycle()
        if (w == 0 || h == 0) return DctWatermark.Extracted.None
        val (y, _, _) = YCbCr.fromRgb(pixels, w, h)
        return DctWatermark.extract(y, w, h)
    }

    private fun decodeToCanonicalRgb(bytes: ByteArray): ByteArray? {
        val bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val w = (bm.width / 8) * 8
        val h = (bm.height / 8) * 8
        if (w == 0 || h == 0) { bm.recycle(); return null }
        val pixelsInt = IntArray(w * h)
        bm.getPixels(pixelsInt, 0, w, 0, 0, w, h)
        bm.recycle()
        val rgb = ByteArray(w * h * 3)
        for (i in pixelsInt.indices) {
            val p = pixelsInt[i]
            rgb[i * 3]     = ((p shr 16) and 0xff).toByte()
            rgb[i * 3 + 1] = ((p shr 8)  and 0xff).toByte()
            rgb[i * 3 + 2] = ( p         and 0xff).toByte()
        }
        return rgb
    }

    private suspend fun runDetectionFallback(bytes: ByteArray): VerifyResult {
        val bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return VerifyResult.Unreadable
        val pixelsInt = IntArray(bm.width * bm.height)
        bm.getPixels(pixelsInt, 0, bm.width, 0, 0, bm.width, bm.height)
        val rgb = ByteArray(bm.width * bm.height * 3)
        for (i in pixelsInt.indices) {
            val p = pixelsInt[i]
            rgb[i * 3]     = ((p shr 16) and 0xff).toByte()
            rgb[i * 3 + 1] = ((p shr 8)  and 0xff).toByte()
            rgb[i * 3 + 2] = ( p         and 0xff).toByte()
        }
        val w = bm.width; val h = bm.height
        bm.recycle()
        val frame = FrameInput(rgb, w, h, System.nanoTime())
        val verdict = detectionFallback.analyze(frame)
        return VerifyResult.NoProvenance(verdict)
    }

    companion object { private const val TAG = "Pramana.Verify" }
}
