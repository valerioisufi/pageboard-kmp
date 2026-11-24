package io.github.valerioisufi.pageboard.sync

import io.github.valerioisufi.pageboard.sync.protocol.SealedOperation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The bytes of the files and of the sealed operations travel as CBOR byte strings (protocol 2,
 * docs/plans/ios-stability.md): a message takes about the bytes it carries, and comes back as it
 * was sent.
 */
class ByteStringsTest {

    private val bytes = ByteArray(64 * 1024) { (it * 31 + 7).toByte() }

    @Test
    fun aChunkOfAFileTakesTheBytesItCarries() {
        val frame = SyncCodec.encode(ResourceChunk("resource", "pdf", 0, bytes.size.toLong(), bytes))
        // As an array of numbers, most bytes took two
        assertTrue(frame.size < bytes.size + 128, "${frame.size} bytes for ${bytes.size}")
        val decoded = assertIs<ResourceChunk>(SyncCodec.decodeOrThrow(frame))
        assertContentEquals(bytes, decoded.bytes)
        assertEquals("resource", decoded.id)
    }

    @Test
    fun aPartOfAnAttachmentTakesTheBytesItCarries() {
        val frame = SyncCodec.encode(AttachmentPart("audio", "v1", 0, bytes.size.toLong(), bytes))
        assertTrue(frame.size < bytes.size + 128, "${frame.size} bytes for ${bytes.size}")
        assertContentEquals(bytes, assertIs<AttachmentPart>(SyncCodec.decodeOrThrow(frame)).bytes)
    }

    @Test
    fun aSealedOperationTakesTheBytesItCarries() {
        val signature = ByteArray(64) { it.toByte() }
        val frame = SyncCodec.encode(Operations(listOf(SealedOperation("replica", 1, bytes, signature))))
        assertTrue(frame.size < bytes.size + signature.size + 128, "${frame.size} bytes for ${bytes.size}")
        val decoded = assertIs<Operations>(SyncCodec.decodeOrThrow(frame)).operations.single()
        assertContentEquals(bytes, decoded.content)
        assertContentEquals(signature, decoded.signature)
    }
}
