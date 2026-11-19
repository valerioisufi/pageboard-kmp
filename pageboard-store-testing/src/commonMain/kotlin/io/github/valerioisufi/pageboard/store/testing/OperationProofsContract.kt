package io.github.valerioisufi.pageboard.store.testing

import io.github.valerioisufi.pageboard.model.Stamp
import io.github.valerioisufi.pageboard.model.change.Operation
import io.github.valerioisufi.pageboard.model.change.OperationId
import io.github.valerioisufi.pageboard.model.change.RenameDocument
import io.github.valerioisufi.pageboard.store.DocumentStore
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The proofs of the operations every [DocumentStore] keeps (see
 * [io.github.valerioisufi.pageboard.store.OperationLog.saveProofs]): by operation and by document,
 * read again when the store is opened again ([reopened]: the same storage, a new store), removed
 * with their operations. Run on an empty store.
 */
public suspend fun checkOperationProofs(store: DocumentStore, reopened: suspend () -> DocumentStore) {
    val document = store.createDocument("Shared")
    val other = store.createDocument("Other")
    val operations = (1L..3L).map { sequence -> Operation(OperationId("bob", sequence), Stamp(sequence, 0, "bob"), RenameDocument("Title $sequence")) }
    operations.forEach { store.persistence.appendOperation(document, it) }
    val ids = operations.map { it.id }
    val proofs = ids.associateWith { id -> byteArrayOf(id.sequence.toByte(), 7, 0, -1) }

    store.saveProofs(document.id, proofs)
    store.saveProofs(other.id, mapOf(ids[0] to byteArrayOf(42)))
    // Kept by document, the operations without one left out
    assertProofs(proofs, store.proofs(document.id, ids + OperationId("cy", 1)))
    assertProofs(mapOf(ids[0] to byteArrayOf(42)), store.proofs(other.id, ids))

    // Read again
    store.flush()
    val again = reopened()
    assertProofs(proofs, again.proofs(document.id, ids))

    // Removed with their operations
    again.compactOperations(document.id, mapOf("bob" to 2L))
    assertProofs(mapOf(ids[2] to proofs.getValue(ids[2])), again.proofs(document.id, ids))
    assertTrue(again.deleteDocument(document.id))
    assertTrue(again.proofs(document.id, ids).isEmpty())
    assertProofs(mapOf(ids[0] to byteArrayOf(42)), again.proofs(other.id, ids))
}

private fun assertProofs(expected: Map<OperationId, ByteArray>, actual: Map<OperationId, ByteArray>) {
    assertEquals(expected.keys, actual.keys)
    for ((id, proof) in expected) assertContentEquals(proof, actual[id], "the proof of $id")
}
