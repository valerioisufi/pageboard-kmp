package io.github.valerioisufi.drawview.platform

import io.github.valerioisufi.drawview.platform.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals

class Sha256Test {
    @Test
    fun theHashesOfKnownContents() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.of(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.of("abc".encodeToByteArray()))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.of("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray())
        )
        // In parts as whole
        val bytes = ByteArray(1_000_000) { 'a'.code.toByte() }
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", Sha256.of(bytes))
        val parts = Sha256()
        bytes.toList().chunked(777).forEach { parts.update(it.toByteArray()) }
        assertEquals(Sha256.of(bytes), parts.digestHex())
    }
}
