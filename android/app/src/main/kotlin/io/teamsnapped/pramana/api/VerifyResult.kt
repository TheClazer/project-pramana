package io.teamsnapped.pramana.api

/**
 * Outcome of [VerifyEngine.verify].
 *
 * Bible Section 8 specifies five UI states. We model them as a sealed
 * hierarchy so the UI does an exhaustive `when` and the compiler catches any
 * future state added without UI handling.
 *
 *  - VerifiedOriginal: signature valid AND content hash matches the manifest.
 *    Render green badge, capture timestamp, device hint, detection details.
 *  - VerifiedButModified: signature valid but content hash mismatch (file has
 *    been re-encoded / cropped / edited since seal). Render amber.
 *  - BrokenSeal: signature invalid, or unknown key, or any other crypto-level
 *    failure. Render red with the reason code.
 *  - NoProvenance: no manifest found in EXIF AND no DCT watermark found.
 *    Falls through to live detection. Carries the [Verdict] from the
 *    classifier so the UI can render it.
 *  - Unreadable: file present but EXIF/DCT can't be parsed at all (corrupt).
 */
sealed class VerifyResult {
    data class VerifiedOriginal(val manifest: Manifest) : VerifyResult()

    data class VerifiedButModified(
        val manifest: Manifest,
        val expectedContentHash: String,
        val actualContentHash:   String
    ) : VerifyResult()

    data class BrokenSeal(val reason: BrokenSealReason) : VerifyResult()

    data class NoProvenance(val verdict: Verdict) : VerifyResult()

    data object Unreadable : VerifyResult()
}

enum class BrokenSealReason(val code: String, val message: String) {
    UNKNOWN_KEY       ("unknown_key",        "Public key for this manifest is not in the trust store."),
    SIGNATURE_INVALID ("signature_invalid",  "Signature did not verify against the manifest's claimed key."),
    MANIFEST_MALFORMED("manifest_malformed", "Manifest is present but does not parse as Pramāṇa v1.x."),
    WATERMARK_ORPHAN  ("watermark_orphan",   "DCT watermark found but the referenced manifest is unavailable.")
}
