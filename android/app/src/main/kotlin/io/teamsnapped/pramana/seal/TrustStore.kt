package io.teamsnapped.pramana.seal

import android.content.Context
import android.util.Log
import java.io.File
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Local registry of known Pramāṇa public keys.
 *
 * Bible Section 8: "the trust store is a small local key registry. The
 * current device's own public key is always present. Future enhancement:
 * bundle a small set of well-known Pramāṇa device keys so we can demo
 * cross-device verification on stage."
 *
 * Storage: a JSON-ish file `trust_store.txt` in `Context.filesDir`, one
 * `keyId\tbase64Pubkey\talgorithm` per line. Plaintext is fine — public keys
 * are not secret.
 */
class TrustStore(context: Context) {

    private val file: File = File(context.filesDir, "trust_store.txt")

    /**
     * Add the device's own public key + any well-known dev keys at first
     * launch. Idempotent.
     */
    fun seedSelf(selfKeyId: String, selfPub: PublicKey, algo: String) {
        if (lookup(selfKeyId) != null) return
        val b64 = Base64.getEncoder().encodeToString(selfPub.encoded)
        file.appendText("$selfKeyId\t$b64\t$algo\n")
        Log.i(TAG, "Trust store seeded with self key.")
    }

    /** Resolve a key by `manifest.keyId`. */
    fun lookup(keyId: String): PublicKey? {
        if (!file.exists()) return null
        var result: PublicKey? = null
        file.forEachLine { line ->
            if (result != null) return@forEachLine
            val parts = line.split("\t")
            if (parts.size == 3 && parts[0] == keyId) {
                result = try {
                    val keyBytes = Base64.getDecoder().decode(parts[1])
                    val spec = X509EncodedKeySpec(keyBytes)
                    val algo = when (parts[2]) {
                        "EC"  -> "EC"
                        "RSA" -> "RSA"
                        else  -> "EC"
                    }
                    KeyFactory.getInstance(algo).generatePublic(spec)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to decode trust-store entry $keyId: ${t.message}")
                    null
                }
            }
        }
        return result
    }

    companion object { private const val TAG = "Pramana.TrustStore" }
}
