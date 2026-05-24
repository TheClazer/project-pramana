package io.teamsnapped.pramana.seal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Sha256Test {

    @Test
    fun `empty input hash matches RFC 6234 vector`() {
        // SHA-256 of empty string = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        val h = Sha256.hex(ByteArray(0))
        assertThat(h).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    }

    @Test
    fun `abc input hash matches RFC 6234 vector`() {
        // SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        val h = Sha256.hex("abc".toByteArray(Charsets.UTF_8))
        assertThat(h).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    @Test
    fun `streaming and one-shot produce same hash`() {
        val data = ByteArray(1000) { (it % 256).toByte() }
        val oneShot = Sha256.hex(data)
        val chunks = sequence {
            yield(data.copyOfRange(0, 333))
            yield(data.copyOfRange(333, 666))
            yield(data.copyOfRange(666, 1000))
        }
        val streamed = Sha256.hexOf(chunks)
        assertThat(streamed).isEqualTo(oneShot)
    }

    @Test
    fun `hash length is 64 hex chars`() {
        assertThat(Sha256.hex("anything".toByteArray()).length).isEqualTo(64)
    }
}
