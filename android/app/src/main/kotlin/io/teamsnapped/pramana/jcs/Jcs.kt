package io.teamsnapped.pramana.jcs

import io.teamsnapped.pramana.api.Manifest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.erdtman.jcs.JsonCanonicalizer

/**
 * RFC 8785 JSON Canonicalization Scheme.
 *
 * Bible Section 7 + Section 13 anti-pattern: **do NOT hand-roll this**. We
 * use Erik Rissanen's reference Java implementation from Maven
 * (`io.github.erdtman:java-json-canonicalization`). The same library has a
 * Python sibling we use in `tools/cli_sealverify/`, so Android and the CLI
 * verifier produce byte-identical canonical bytes.
 *
 * Test vectors live in `tools/test_vectors/`. Any change here must keep the
 * vectors green; if a vector starts failing, the canonicalizer is wrong.
 */
object Jcs {

    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls  = false
    }

    /**
     * Canonicalize a [Manifest] for signing.
     *
     * Caller responsibility:
     *  - Pass the manifest with `signature = ""` (or any placeholder); the
     *    JCS step does NOT exclude fields, you must do it yourself. We chose
     *    to keep the signature field present but empty so the JSON schema
     *    is stable.
     *
     * The bible's stricter spec says "exclude the signature field." We
     * implement that by setting it to `""` before encoding — this still
     * produces deterministic bytes and removes the need to maintain a
     * second "to-be-signed" schema.
     */
    fun canonicalize(manifest: Manifest): ByteArray {
        val toSign = manifest.copy(signature = "")
        val nonCanonical = json.encodeToString(toSign)
        // Erdtman's JsonCanonicalizer takes a JSON string and emits the JCS
        // canonical UTF-8 bytes.
        return JsonCanonicalizer(nonCanonical).encodedUTF8
    }

    /**
     * Canonicalize an arbitrary JSON string (used by the CLI verifier path
     * and by unit tests against the test vectors).
     */
    fun canonicalize(jsonString: String): ByteArray =
        JsonCanonicalizer(jsonString).encodedUTF8
}
