package io.github.valerioisufi.drawview.sync.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor

/**
 * The operations travel sealed with the key of their document: their identity in clear, their
 * content readable with the key only, neither of them changed on the way.
 */
class SealedOperationTest {
    private val key = DocumentKey.generate()
    private val content = "the change of the operation".encodeToByteArray()

    @Test
    fun anOperationIsOpenedWithTheKeyOfItsDocument() {
        val sealed = OperationSeal(key).seal("ada", 7, content)
        assertEquals("ada", sealed.replica)
        assertEquals(7, sealed.sequence)
        assertFalse(sealed.content.decodeToString().contains("change"), "the content is not in clear")
        assertContentEquals(content, OperationSeal(key).open(sealed))
    }

    @Test
    fun anotherKeyCannotOpenIt() {
        val sealed = OperationSeal(key).seal("ada", 7, content)
        assertFailsWith<SyncException> { OperationSeal(DocumentKey.generate()).open(sealed) }
    }

    @Test
    fun itsIdentityCannotBeChangedOnTheWay() {
        val sealed = OperationSeal(key).seal("ada", 7, content)
        // Given to another replica, or as another operation of the same one (e.g. by a relay)
        assertFailsWith<SyncException> { OperationSeal(key).open(SealedOperation("bob", 7, sealed.content)) }
        assertFailsWith<SyncException> { OperationSeal(key).open(SealedOperation("ada", 8, sealed.content)) }
        // Nor its content
        val changed = sealed.content.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertFailsWith<SyncException> { OperationSeal(key).open(SealedOperation("ada", 7, changed)) }
    }

    @Test
    fun theSameContentIsSealedDifferentlyEachTime() {
        val seal = OperationSeal(key)
        assertFalse(seal.seal("ada", 7, content).content.contentEquals(seal.seal("ada", 7, content).content))
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun theFirstFrameAndTheSealedOperationsKeepTheirForm() {
        val frame = OpenFrame(PROTOCOL_VERSION, key.tag)
        assertEquals(frame, OpenFrame.decode(frame.encode()))
        assertEquals(null, OpenFrame.decode(byteArrayOf(1, 2, 3)))
        val sealed = OperationSeal(key).seal("ada", 7, content)
        val read = Cbor.decodeFromByteArray(SealedOperation.serializer(), Cbor.encodeToByteArray(SealedOperation.serializer(), sealed))
        assertContentEquals(content, OperationSeal(key).open(read))
    }
}
