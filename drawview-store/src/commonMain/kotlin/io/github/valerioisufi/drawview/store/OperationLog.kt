package io.github.valerioisufi.drawview.store

import io.github.valerioisufi.drawview.model.Stamp
import io.github.valerioisufi.drawview.model.change.Operation

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
}
