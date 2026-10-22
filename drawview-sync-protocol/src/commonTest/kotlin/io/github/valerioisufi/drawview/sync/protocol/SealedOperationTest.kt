package io.github.valerioisufi.drawview.sync.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor

/**
 * The operations travel sealed with the key of their document: their identity in clear, their
 * content readable with the key only, neither of them changed on the way; and signed by the device
 * that made them, which no other device can pass for.
 */
class SealedOperationTest {
    private val key = DocumentKey.generate()
    private val content = "the change of the operation".encodeToByteArray()
    private val ada = DeviceIdentity.generate()
    private val proof = OperationProof.sign(ada, "doc", "ada", 7, content)

    @Test
    fun anOperationIsOpenedWithTheKeyOfItsDocument() {
        val sealed = OperationSeal(key).seal("ada", 7, proof)
        assertEquals("ada", sealed.replica)
        assertEquals(7, sealed.sequence)
        assertFalse(sealed.content.decodeToString().contains("change"), "the content is not in clear")
        val opened = OperationSeal(key).open(sealed)
        assertContentEquals(content, opened.content)
        assertTrue(opened.isBy(ada.publicKey, "doc", "ada", 7))
    }

    @Test
    fun anotherKeyCannotOpenIt() {
        val sealed = OperationSeal(key).seal("ada", 7, proof)
        assertFailsWith<SyncException> { OperationSeal(DocumentKey.generate()).open(sealed) }
    }

    @Test
    fun itsIdentityCannotBeChangedOnTheWay() {
        val sealed = OperationSeal(key).seal("ada", 7, proof)
        // Given to another replica, or as another operation of the same one (e.g. by a relay)
        assertFailsWith<SyncException> { OperationSeal(key).open(SealedOperation("bob", 7, sealed.content, sealed.signature)) }
        assertFailsWith<SyncException> { OperationSeal(key).open(SealedOperation("ada", 8, sealed.content, sealed.signature)) }
        // Nor its content
        val changed = sealed.content.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertFailsWith<SyncException> { OperationSeal(key).open(SealedOperation("ada", 7, changed, sealed.signature)) }
    }

    @Test
    fun theSameContentIsSealedDifferentlyEachTime() {
        val seal = OperationSeal(key)
        assertFalse(seal.seal("ada", 7, proof).content.contentEquals(seal.seal("ada", 7, proof).content))
    }

    @Test
    fun anOperationIsByTheDeviceThatSignedItOnly() {
        assertTrue(proof.isBy(ada.publicKey, "doc", "ada", 7))
        // Another device, document, replica or sequence, another content
        assertFalse(proof.isBy(DeviceIdentity.generate().publicKey, "doc", "ada", 7))
        assertFalse(proof.isBy(ada.publicKey, "other", "ada", 7))
        assertFalse(proof.isBy(ada.publicKey, "doc", "bob", 7))
        assertFalse(proof.isBy(ada.publicKey, "doc", "ada", 8))
        assertFalse(OperationProof("another change".encodeToByteArray(), proof.signature).isBy(ada.publicKey, "doc", "ada", 7))
        // Parts that read the same once joined are not the same
        val joined = OperationProof.sign(ada, "doc|1:x", "ada", 7, content)
        assertFalse(joined.isBy(ada.publicKey, "doc", "1:x|ada", 7))
        // A device signing for another one: the proof is its own
        val bob = DeviceIdentity.generate()
        assertFalse(OperationProof.sign(bob, "doc", "ada", 7, content).isBy(ada.publicKey, "doc", "ada", 7))
        // Kept and read again (the key of the document is not in it)
        val kept = assertNotNull(OperationProof.decode(proof.encode()))
        assertTrue(kept.isBy(ada.publicKey, "doc", "ada", 7))
        assertNull(OperationProof.decode(byteArrayOf(1, 2, 3)))
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun theFirstFrameAndTheSealedOperationsKeepTheirForm() {
        val frame = OpenFrame(PROTOCOL_VERSION, key.tag)
        assertEquals(frame, OpenFrame.decode(frame.encode()))
        assertEquals(null, OpenFrame.decode(byteArrayOf(1, 2, 3)))
        val sealed = OperationSeal(key).seal("ada", 7, proof)
        val read = Cbor.decodeFromByteArray(SealedOperation.serializer(), Cbor.encodeToByteArray(SealedOperation.serializer(), sealed))
        assertContentEquals(content, OperationSeal(key).open(read).content)
        assertTrue(OperationSeal(key).open(read).isBy(ada.publicKey, "doc", "ada", 7))
    }
}
