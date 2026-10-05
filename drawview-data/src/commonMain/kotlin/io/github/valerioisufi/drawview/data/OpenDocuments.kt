package io.github.valerioisufi.drawview.data

import io.github.valerioisufi.drawview.core.change.DocumentSession
import io.github.valerioisufi.drawview.core.change.Operation
import io.github.valerioisufi.drawview.core.controller.ChangePersister
import io.github.valerioisufi.drawview.core.controller.PersistenceQueue
import io.github.valerioisufi.drawview.core.model.DrawDocument
import io.github.valerioisufi.drawview.core.model.DrawResource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The documents of [store] being changed, each by a single [DocumentSession] shared by whoever
 * changes it: the editor and the synchronization with the other devices (which also changes the
 * documents that are not open in an editor). The changes of a session are stored as they are
 * applied.
 *
 * The sessions are used on [dispatcher], the thread of the documents (the UI thread in the
 * applications); [scope] runs the writes.
 */
class OpenDocuments(
    private val store: DocumentStore,
    val dispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope
) {
    private val mutex = Mutex()
    private val open = mutableMapOf<String, OpenDocument>()

    /**
     * The open document [documentId] (loaded when nobody uses it yet), or `null` when it does not
     * exist. Every [open] is followed by a [close].
     */
    suspend fun open(documentId: String): OpenDocument? = mutex.withLock {
        open[documentId]?.let { return it.also { it.users++ } }
        val document = store.loadDocument(documentId) ?: return null
        val session = DocumentSession(document, store.replicaId(), store.versionVector(documentId))
        OpenDocument(session, dispatcher, PersistenceQueue(scope, store.persistence), store).also { open[documentId] = it }
    }

    /** This device in the changes of the documents (see [DocumentSession.replica]). */
    suspend fun replicaId(): String = store.replicaId()

    /**
     * Stores [document] (received from another device) in [folderId] and opens it (see
     * [DocumentStore.insertDocument]).
     */
    suspend fun insert(document: DrawDocument, folderId: String?): OpenDocument {
        store.insertDocument(document, folderId)
        return checkNotNull(open(document.id))
    }

    /**
     * Stops using [document]: written and closed when nobody uses it anymore (before it can be
     * opened again: loaded with all its changes).
     */
    suspend fun close(document: OpenDocument) = mutex.withLock {
        document.users--
        if (document.users > 0) return@withLock
        document.flush()
        store.flush()
        store.closeDocument(document.document.id)
        open.remove(document.document.id)
    }
}

/**
 * A document of [OpenDocuments]: its [session] is used on [dispatcher] (see [edit]).
 */
class OpenDocument internal constructor(
    val session: DocumentSession,
    val dispatcher: CoroutineDispatcher,
    private val queue: PersistenceQueue,
    private val store: DocumentStore
) {
    internal var users = 1
    private val received = MutableSharedFlow<DrawResource>(extraBufferCapacity = 64)

    val document: DrawDocument get() = session.document

    /** The resources whose file arrived from another device (to be drawn again). */
    val resourcesReceived: SharedFlow<DrawResource> = received

    init {
        session.addObserver(ChangePersister(queue::enqueue))
    }

    /** Suspends until the changes applied so far are stored. */
    suspend fun flush() = queue.flush()

    /**
     * The operations of the document after [known] (a version vector), in stamp order: the ones
     * applied so far, stored or not yet.
     */
    suspend fun operationsAfter(known: Map<String, Long>): List<Operation> {
        flush()
        return store.operationsAfter(document.id, known)
    }

    /** The file of the resource [resourceId] (received from another device) is [path]. */
    suspend fun resourceReceived(resourceId: String, path: String) {
        val resource = edit { session -> session.document.findResource(resourceId)?.also { it.path = path } } ?: return
        queue.enqueue { saveResource(document, resource) }
        received.emit(resource)
    }

    /** Runs [block] with the session on the thread of the document. */
    suspend fun <T> edit(block: (DocumentSession) -> T): T = withContext(dispatcher) { block(session) }
}
