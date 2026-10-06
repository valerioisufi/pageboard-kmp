package io.github.valerioisufi.drawview.sync

import io.github.valerioisufi.drawview.data.DocumentStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [SharedDocuments] kept by a [DocumentStore] (see [DocumentStore.sharingSecrets]); the key of a
 * document joined but not received yet is kept in memory until it arrives (see [SyncEngine]).
 */
class StoreSharedDocuments(private val store: DocumentStore) : SharedDocuments {
    private val mutex = Mutex()
    private val waiting = mutableMapOf<String, DocumentKey>()

    override suspend fun keyOf(documentId: String): DocumentKey? =
        mutex.withLock { waiting[documentId] } ?: store.sharingSecrets()[documentId]?.let(DocumentKey::decode)

    override suspend fun add(documentId: String, key: DocumentKey) {
        if (store.documents.first().none { it.id == documentId }) {
            mutex.withLock { waiting[documentId] = key }
            return
        }
        store.setSharingSecret(documentId, key.encode())
        mutex.withLock { waiting.remove(documentId) }
    }

    override suspend fun remove(documentId: String) {
        mutex.withLock { waiting.remove(documentId) }
        store.setSharingSecret(documentId, null)
    }

    override suspend fun all(): Map<String, DocumentKey> {
        val stored = store.sharingSecrets().mapNotNull { (id, secret) -> DocumentKey.decode(secret)?.let { id to it } }.toMap()
        return stored + mutex.withLock { waiting.toMap() }
    }
}
