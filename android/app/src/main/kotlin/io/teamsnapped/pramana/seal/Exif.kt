package io.teamsnapped.pramana.seal

import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Base64

/**
 * EXIF UserComment (tag 0x9286) read/write for the Pramāṇa manifest.
 *
 * Bible Section 7. We base64 the canonical manifest JSON and stuff it into
 * `UserComment`. `androidx.exifinterface` is the official library — the
 * third-party fork is explicitly NOT used.
 *
 * Reading and writing both need to round-trip through a temp file because
 * `ExifInterface` does NOT support arbitrary InputStream/OutputStream for
 * writes — only File or FileDescriptor. We keep the temp file in
 * `Context.cacheDir`-style scratch space; the caller passes a working
 * directory.
 */
object Exif {

    private const val TAG    = "Pramana.Exif"

    /**
     * Sentinel prefix so we can tell a Pramāṇa manifest apart from a
     * normal UserComment string a different app may have written. Bible
     * Section 11 redundancy: if a third party stuffs UserComment with
     * something else, we ignore it instead of crashing the verify path.
     */
    private const val PREFIX = "PMNA1:"

    /**
     * Write the manifest into the image's UserComment field.
     *
     * @param imageBytes JPEG bytes (other formats are not currently
     *   supported by EXIF — bible Section 11 fallback is a sidecar `.pmna`
     *   file in that case).
     * @param manifestJsonCanonical the JCS bytes (UTF-8) of the manifest;
     *   we base64-encode for safe storage in the EXIF string field.
     * @param scratchDir a writable directory for the round-trip temp file.
     */
    fun embedUserComment(
        imageBytes: ByteArray,
        manifestJsonCanonical: ByteArray,
        scratchDir: File
    ): ByteArray {
        if (!scratchDir.exists()) scratchDir.mkdirs()
        val tmp = File.createTempFile("pmna_exif_", ".jpg", scratchDir)
        try {
            FileOutputStream(tmp).use { it.write(imageBytes) }

            val exif = ExifInterface(tmp.absolutePath)
            val b64  = Base64.getEncoder().encodeToString(manifestJsonCanonical)
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, PREFIX + b64)
            exif.saveAttributes()

            return FileInputStream(tmp).use { it.readBytes() }
        } finally {
            tmp.delete()
        }
    }

    /**
     * Read the Pramāṇa manifest bytes out of UserComment, or null if no
     * such tag is present.
     */
    fun readUserComment(imageBytes: ByteArray): ByteArray? {
        return try {
            val exif = ExifInterface(ByteArrayInputStream(imageBytes))
            val raw  = exif.getAttribute(ExifInterface.TAG_USER_COMMENT)
                ?: return null
            if (!raw.startsWith(PREFIX)) return null
            Base64.getDecoder().decode(raw.removePrefix(PREFIX))
        } catch (t: Throwable) {
            Log.w(TAG, "EXIF read failed: ${t.message}")
            null
        }
    }
}
