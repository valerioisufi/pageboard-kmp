package io.github.valerioisufi.drawview.sync

import io.github.valerioisufi.drawview.data.DocumentStore
import io.github.valerioisufi.drawview.data.OpenDocuments
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [SharedDocuments] kept by a [DocumentStore] (see [DocumentStore.sharingSecrets]); the sharing
 * of a document joined but not received yet is kept in memory until it arrives (see
 * [SyncEngine]). Whether this device may only read a document goes to its open session too
 * ([OpenDocuments.setReadOnly]).
 */
class StoreSharedDocuments(private val store: DocumentStore, private val documents: OpenDocuments? = null) : SharedDocuments {
    private val mutex = Mutex()
    private val waiting = mutableMapOf<String, SharingState>()

    override suspend fun stateOf(documentId: String): SharingState? =
        mutex.withLock { waiting[documentId] } ?: store.sharingSecrets()[documentId]?.let(SharingState::decode)

    override suspend fun save(documentId: String, state: SharingState, readOnly: Boolean) {
        if (store.documents.first().none { it.id == documentId }) {
            mutex.withLock { waiting[documentId] = state }
            return
        }
        store.setSharingSecret(documentId, state.encode(), readOnly)
        mutex.withLock { waiting.remove(documentId) }
        documents?.setReadOnly(documentId, readOnly)
    }

    override suspend fun remove(documentId: String) {
        mutex.withLock { waiting.remove(documentId) }
        store.setSharingSecret(documentId, null)
        documents?.setReadOnly(documentId, false)
    }

    override suspend fun all(): Map<String, SharingState> {
        val stored = store.sharingSecrets().mapNotNull { (id, secret) -> SharingState.decode(secret)?.let { id to it } }.toMap()
        return stored + mutex.withLock { waiting.toMap() }
    }
}
