package io.teamsnapped.pramana.dct

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

class ReedSolomonTest {

    @Test
    fun `encode appends parity bytes`() {
        val data = ByteArray(32) { it.toByte() }
        val out = ReedSolomon.encode(data, 16)
        assertThat(out.size).isEqualTo(48)
        for (i in 0 until 32) assertThat(out[i]).isEqualTo(data[i])
    }

    @Test
    fun `intact codeword passes syndrome check`() {
        val data = ByteArray(32) { (it * 17).toByte() }
        val cw = ReedSolomon.encode(data, 16)
        assertThat(ReedSolomon.isIntact(cw, 16)).isTrue()
    }

    @Test
    fun `single bit flip is detected`() {
        val data = ByteArray(32) { (it * 5).toByte() }
        val cw = ReedSolomon.encode(data, 16)
        // Flip one bit in the data region
        cw[10] = (cw[10].toInt() xor 0x04).toByte()
        assertThat(ReedSolomon.isIntact(cw, 16)).isFalse()
    }

    @Test
    fun `parity bit flip is detected`() {
        val data = ByteArray(32) { (255 - it).toByte() }
        val cw = ReedSolomon.encode(data, 16)
        // Flip one bit in the parity region
        cw[40] = (cw[40].toInt() xor 0x80).toByte()
        assertThat(ReedSolomon.isIntact(cw, 16)).isFalse()
    }

    @Test
    fun `random payloads all encode-then-detect-as-intact`() {
        val rng = Random(42L)
        repeat(50) {
            val len = 32
            val data = ByteArray(len).also { rng.nextBytes(it) }
            val cw = ReedSolomon.encode(data, 16)
            assertThat(ReedSolomon.isIntact(cw, 16)).isTrue()
        }
    }
}
