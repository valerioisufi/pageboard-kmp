package io.github.valerioisufi.pageboard.store

import io.github.valerioisufi.pageboard.model.Stamp
import io.github.valerioisufi.pageboard.model.change.Operation
import io.github.valerioisufi.pageboard.model.change.OperationId

/**
 * The log of the operations of the documents (appended by [DocumentStore.persistence]), as the
 * sessions and the synchronization with the other devices read and compact it.
 */
public interface OperationLog {
    /** This device in the changes of the documents (see `DocumentSession.replica`). */
    public suspend fun replicaId(): String

    /** The last operation of every replica in the log of the document [documentId]. */
    public suspend fun versionVector(documentId: String): Map<String, Long>

    /** The operations of the document [documentId] after [known] (a version vector), in stamp order. */
    public suspend fun operationsAfter(documentId: String, known: Map<String, Long>): List<Operation>

    /**
     * Removes from the log of [documentId] its operations up to [upTo] (a version vector: the ones
     * every device working on it has), still counted in its [versionVector]: the stamps of the last
     * operations removed, by replica.
     */
    public suspend fun compactOperations(documentId: String, upTo: Map<String, Long>): Map<String, Stamp>

    /** The last operation of every replica no longer in the log of [documentId] (see [compactOperations]). */
    public suspend fun compactedOperations(documentId: String): Map<String, Long>

    /**
     * The proofs kept of the operations [ids] of [documentId] (see [saveProofs]), by operation: the
     * ones without a proof are left out.
     */
    public suspend fun proofs(documentId: String, ids: Collection<OperationId>): Map<OperationId, ByteArray> = emptyMap()

    /**
     * Keeps [proofs] of operations of [documentId]: what the synchronization gives the other
     * devices with them (who made each one, signed), opaque bytes to the store. They go with their
     * operations ([compactOperations], the deletion of the document). A store keeping none gives
     * the other devices the operations of its own device only.
     */
    public suspend fun saveProofs(documentId: String, proofs: Map<OperationId, ByteArray>) {}
}
