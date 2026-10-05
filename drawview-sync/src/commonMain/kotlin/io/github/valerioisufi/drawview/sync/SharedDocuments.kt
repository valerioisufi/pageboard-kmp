package io.github.valerioisufi.drawview.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The documents this device shares or has joined, with their keys (kept by the application: the
 * keys are secrets).
 */
interface SharedDocuments {
    /** The key of the document [documentId], or `null` when it is not shared. */
    suspend fun keyOf(documentId: String): DocumentKey?

    suspend fun add(documentId: String, key: DocumentKey)

    suspend fun remove(documentId: String)

    /** Every shared document, with its key. */
    suspend fun all(): Map<String, DocumentKey>
}

/** [SharedDocuments] kept in memory (tests, or documents shared until the application ends). */
class InMemorySharedDocuments : SharedDocuments {
    private val mutex = Mutex()
    private val keys = mutableMapOf<String, DocumentKey>()

    override suspend fun keyOf(documentId: String): DocumentKey? = mutex.withLock { keys[documentId] }

    override suspend fun add(documentId: String, key: DocumentKey) = mutex.withLock { keys[documentId] = key }

    override suspend fun remove(documentId: String) {
        mutex.withLock { keys.remove(documentId) }
    }

    override suspend fun all(): Map<String, DocumentKey> = mutex.withLock { keys.toMap() }
}
