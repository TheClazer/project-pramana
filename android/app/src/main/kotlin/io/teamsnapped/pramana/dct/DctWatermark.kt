package io.teamsnapped.pramana.dct

import io.teamsnapped.pramana.seal.Sha256
import kotlin.math.abs

/**
 * The invisible authenticity layer — bible Section 7, "core, not stretch."
 *
 * What we embed:
 *
 *   ┌────────────────────────── 32-byte payload ──────────────────────────┐
 *   │ 1 B  │ 16 B (manifest fingerprint   │ 8 B (key fingerprint │ 7 B    │
 *   │ ver  │  = first 16 of SHA-256(JCS)) │  = first 8 of keyId) │ rsv 0  │
 *   └──────┴──────────────────────────────┴──────────────────────┴────────┘
 *   + 16-byte Reed-Solomon parity (over the 32 payload bytes)
 *   = 48-byte codeword = 384 bits = 384 blocks of 8x8 luminance modified.
 *
 * A 640×480 image has 80 × 60 = 4800 blocks, so 384 is comfortably below
 * capacity. We use a deterministic block selection (top-to-bottom,
 * left-to-right) for v1.1 — randomized selection would survive cropping
 * attacks better but the bible scope is core, not adversarial.
 */
object DctWatermark {

    /** Pramāṇa watermark version byte. Bumping this is a breaking change. */
    const val VERSION_BYTE: Byte = 0x01

    /** Payload length in bytes (before RS parity). */
    const val PAYLOAD_BYTES = 32

    /** Reed-Solomon parity length. ~half the payload per bible Section 7. */
    const val ECC_BYTES = 16

    /** Total codeword length in bytes. */
    const val CODEWORD_BYTES = PAYLOAD_BYTES + ECC_BYTES

    /** Total bits embedded across blocks. */
    const val CODEWORD_BITS = CODEWORD_BYTES * 8         // 384

    /**
     * Per-bit DCT coefficient delta. Larger = more robust to JPEG quant,
     * but visible artifacts above ~20 in 8-bit luminance. 12 is the sweet
     * spot for JPEG quality 70+ (the threshold the bible cites).
     */
    private const val DELTA = 12f

    // ------------------------------------------------------------------ //
    //  Public payload helpers
    // ------------------------------------------------------------------ //

    /**
     * Build the 32-byte payload from a canonical manifest and its key id.
     *
     * @param manifestJcsBytes RFC 8785 JCS bytes of the manifest (i.e.
     *   `Jcs.canonicalize(manifest)`).
     * @param keyIdHex SHA-256 hex of the public key (must be at least 16
     *   hex chars = 8 bytes).
     */
    fun buildPayload(manifestJcsBytes: ByteArray, keyIdHex: String): ByteArray {
        require(keyIdHex.length >= 16) { "keyIdHex too short" }
        val out = ByteArray(PAYLOAD_BYTES)
        out[0] = VERSION_BYTE

        val manifestFp = Sha256.bytes(manifestJcsBytes).copyOf(16)
        System.arraycopy(manifestFp, 0, out, 1, 16)

        // keyId is hex; convert first 16 chars (= 8 bytes) into the slot.
        for (i in 0 until 8) {
            val hi = Character.digit(keyIdHex[i * 2],     16)
            val lo = Character.digit(keyIdHex[i * 2 + 1], 16)
            out[17 + i] = ((hi shl 4) or lo).toByte()
        }
        // Last 7 bytes reserved — leave as zero. RS will detect drift.
        return out
    }

    // ------------------------------------------------------------------ //
    //  Embed / extract
    // ------------------------------------------------------------------ //

    /**
     * Embed `payload` (must be PAYLOAD_BYTES) into the luminance channel.
     * Mutates `y` in place.
     */
    fun embed(y: FloatArray, width: Int, height: Int, payload: ByteArray) {
        require(payload.size == PAYLOAD_BYTES) { "payload must be $PAYLOAD_BYTES bytes" }
        val codeword = ReedSolomon.encode(payload, ECC_BYTES)
        require(codeword.size == CODEWORD_BYTES)

        val blocksX = width / 8
        val blocksY = height / 8
        check(blocksX * blocksY >= CODEWORD_BITS) {
            "image too small for watermark: need >= $CODEWORD_BITS 8x8 blocks, have ${blocksX*blocksY}"
        }

        val block = FloatArray(64)
        for (bit in 0 until CODEWORD_BITS) {
            val bx = bit % blocksX
            val by = bit / blocksX
            readBlock(y, width, bx, by, block)
            DctMath.dct(block)

            val byteIdx = bit / 8
            val bitInByte = 7 - (bit % 8)
            val want = (codeword[byteIdx].toInt() shr bitInByte) and 1

            // Quantize the mid-freq coefficient to ±DELTA depending on bit.
            // QIM (quantization index modulation) — robust to JPEG.
            val k = DctMath.ZIGZAG[DctMath.EMBED_ZIGZAG_INDEX]
            val q = 2 * DELTA
            val cur = block[k]
            // Snap to nearest grid multiple of (2*DELTA) shifted by ±DELTA/2.
            val target = if (want == 1) {
                // odd multiple of DELTA (..., -3D, -D, +D, +3D, ...)
                val n = Math.round((cur - DELTA) / q).toFloat()
                n * q + DELTA
            } else {
                // even multiple of DELTA (..., -2D, 0, +2D, ...)
                val n = Math.round(cur / q).toFloat()
                n * q
            }
            block[k] = target

            DctMath.idct(block)
            writeBlock(y, width, bx, by, block)
        }
    }

    /**
     * Try to extract a watermark. Returns:
     *  - `Found(payload)` if RS validates,
     *  - `Damaged(rawCodeword)` if bits read but RS fails (still indicates
     *    a Pramāṇa origin claim — bible Section 7),
     *  - `None` if the image is too small / no plausible bits found.
     */
    fun extract(y: FloatArray, width: Int, height: Int): Extracted {
        val blocksX = width / 8
        val blocksY = height / 8
        if (blocksX * blocksY < CODEWORD_BITS) return Extracted.None

        val block = FloatArray(64)
        val codeword = ByteArray(CODEWORD_BYTES)
        var anyNonzero = false

        for (bit in 0 until CODEWORD_BITS) {
            val bx = bit % blocksX
            val by = bit / blocksX
            readBlock(y, width, bx, by, block)
            DctMath.dct(block)

            val k = DctMath.ZIGZAG[DctMath.EMBED_ZIGZAG_INDEX]
            val coef = block[k]
            // Recover bit: distance to nearest odd-grid vs even-grid.
            val q = 2 * DELTA
            val toEven = abs(coef - Math.round(coef / q) * q)
            val toOdd  = abs(coef - (Math.round((coef - DELTA) / q) * q + DELTA))
            val bitVal = if (toOdd < toEven) 1 else 0
            if (bitVal == 1) anyNonzero = true

            val byteIdx = bit / 8
            val bitInByte = 7 - (bit % 8)
            if (bitVal == 1) {
                codeword[byteIdx] = (codeword[byteIdx].toInt() or (1 shl bitInByte)).toByte()
            }
        }

        if (!anyNonzero) return Extracted.None
        return if (ReedSolomon.isIntact(codeword, ECC_BYTES)) {
            Extracted.Found(codeword.copyOfRange(0, PAYLOAD_BYTES))
        } else {
            Extracted.Damaged(codeword)
        }
    }

    sealed class Extracted {
        data class Found(val payload: ByteArray) : Extracted() {
            override fun equals(other: Any?) = this === other
            override fun hashCode() = System.identityHashCode(this)
        }
        data class Damaged(val rawCodeword: ByteArray) : Extracted() {
            override fun equals(other: Any?) = this === other
            override fun hashCode() = System.identityHashCode(this)
        }
        data object None : Extracted()
    }

    // ------------------------------------------------------------------ //
    //  Block I/O helpers
    // ------------------------------------------------------------------ //

    private fun readBlock(y: FloatArray, width: Int, bx: Int, by: Int, out: FloatArray) {
        val x0 = bx * 8; val y0 = by * 8
        for (j in 0 until 8) {
            for (i in 0 until 8) {
                out[j * 8 + i] = y[(y0 + j) * width + (x0 + i)]
            }
        }
    }

    private fun writeBlock(y: FloatArray, width: Int, bx: Int, by: Int, src: FloatArray) {
        val x0 = bx * 8; val y0 = by * 8
        for (j in 0 until 8) {
            for (i in 0 until 8) {
                y[(y0 + j) * width + (x0 + i)] = src[j * 8 + i].coerceIn(0f, 255f)
            }
        }
    }
}
