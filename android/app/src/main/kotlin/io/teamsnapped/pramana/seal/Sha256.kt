package io.teamsnapped.pramana.seal

import java.security.MessageDigest

/**
 * Thin convenience over `java.security.MessageDigest("SHA-256")`.
 *
 * Bible Section 10: hashing is "standard library, zero risk." This file
 * exists only to centralize the hex/Base64 formatting so both Sealer and
 * Verifier produce identical strings.
 */
object Sha256 {

    fun bytes(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input)

    fun hex(input: ByteArray): String =
        bytes(input).joinToString("") { "%02x".format(it) }

    /** Streaming variant — useful for sealing large videos without loading all of them. */
    fun hexOf(chunks: Sequence<ByteArray>): String {
        val md = MessageDigest.getInstance("SHA-256")
        chunks.forEach { md.update(it) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
