package io.github.valerioisufi.drawview.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The documents this device shares, has joined or is joining, with what it knows of their
 * sharing ([SharingState]: keys, owner, membership...), kept by the application (they are
 * secrets).
 */
interface SharedDocuments {
    /** The sharing of the document [documentId], or `null` when it is not shared. */
    suspend fun stateOf(documentId: String): SharingState?

    /** Keeps [state]; [readOnly]: this device may only read the document. */
    suspend fun save(documentId: String, state: SharingState, readOnly: Boolean = false)

    suspend fun remove(documentId: String)

    /** Every shared document, with its sharing. */
    suspend fun all(): Map<String, SharingState>
}

/** [SharedDocuments] kept in memory (tests, or documents shared until the application ends). */
class InMemorySharedDocuments : SharedDocuments {
    private val mutex = Mutex()
    private val states = mutableMapOf<String, SharingState>()

    override suspend fun stateOf(documentId: String): SharingState? = mutex.withLock { states[documentId] }

    override suspend fun save(documentId: String, state: SharingState, readOnly: Boolean) = mutex.withLock { states[documentId] = state }

    override suspend fun remove(documentId: String) {
        mutex.withLock { states.remove(documentId) }
    }

    override suspend fun all(): Map<String, SharingState> = mutex.withLock { states.toMap() }
}
