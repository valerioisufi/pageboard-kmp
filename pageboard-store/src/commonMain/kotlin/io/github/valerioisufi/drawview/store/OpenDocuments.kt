package io.github.valerioisufi.drawview.store

import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawResource
import io.github.valerioisufi.drawview.model.StrokeModeller
import io.github.valerioisufi.drawview.model.TrashRetention
import io.github.valerioisufi.drawview.model.change.ChangePersister
import io.github.valerioisufi.drawview.model.change.DocumentSession
import io.github.valerioisufi.drawview.model.change.Operation
import io.github.valerioisufi.drawview.model.change.OperationId
import io.github.valerioisufi.drawview.model.change.PersistenceQueue
import io.github.valerioisufi.drawview.model.change.RenameDocument
import io.github.valerioisufi.drawview.platform.currentTimeMillis
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The documents of [store] being changed, each by a single [DocumentSession] shared by whoever
 * changes it: the editor and the synchronization with the other devices (which also changes the
 * documents that are not open in an editor). The changes of a session are stored as they are
 * applied. An application keeps one per store, given to the library, the editor, the
 * synchronization and the export; [clock] tells how long the pages removed are kept (see
 * [OpenDocument.compact]).
 *
 * The sessions are used on [dispatcher], the thread of the documents (the UI thread in the
 * applications); [scope] runs the writes.
 */
public class OpenDocuments(
    public val store: DocumentStore,
    public val dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val clock: () -> Long = ::currentTimeMillis
) {
    private val mutex = Mutex()
    private val open = mutableMapOf<String, OpenDocument>()

    /** Models the strokes of the documents received as data (the one of the store). */
    public val strokeModeller: StrokeModeller get() = store.strokeModeller

    /**
     * The open document [documentId] (loaded when nobody uses it yet), or `null` when it does not
     * exist. Every [open] is followed by a [close].
     */
    public suspend fun open(documentId: String): OpenDocument? = mutex.withLock {
        open[documentId]?.let { return it.also { it.users++ } }
        val document = store.loadDocument(documentId) ?: return null
        val session = DocumentSession(document, store.replicaId(), store.versionVector(documentId))
        session.isReadOnly = store.isReadOnly(documentId)
        OpenDocument(session, dispatcher, PersistenceQueue(scope, store.persistence), store, clock).also { open[documentId] = it }
    }

    /**
     * Renames the document [documentId] by a change of it (the devices it is shared with rename it
     * too); `false` when another document of its folder has the name [name].
     */
    public suspend fun rename(documentId: String, name: String): Boolean {
        if (!store.isDocumentNameAvailable(documentId, name)) return false
        val document = open(documentId) ?: return false
        try {
            document.edit { it.submit(RenameDocument(name)) }
        } finally {
            close(document)
        }
        return true
    }

    /** This device in the changes of the documents (see [DocumentSession.replica]). */
    public suspend fun replicaId(): String = store.replicaId()

    /**
     * Stores [document] (received from another device, with the changes of the operations
     * [versionVector]) in [folderId] and opens it (see [DocumentStore.insertDocument]).
     */
    public suspend fun insert(document: DrawDocument, folderId: String?, versionVector: Map<String, Long> = emptyMap()): OpenDocument {
        store.insertDocument(document, folderId, versionVector)
        return checkNotNull(open(document.id))
    }

    /** This device may only read the document [documentId] (or change it again): its open session too. */
    public suspend fun setReadOnly(documentId: String, readOnly: Boolean) {
        val document = mutex.withLock { open[documentId] } ?: return
        document.edit { it.isReadOnly = readOnly }
    }

    /** A file kept with the content of hash [hash] (of any document), `null` when there is none. */
    public suspend fun fileWithContent(hash: String): String? = if (hash.isEmpty()) null else store.persistence.fileWithContent(hash)

    /**
     * Stops using [document]: written and closed when nobody uses it anymore (before it can be
     * opened again: loaded with all its changes).
     */
    public suspend fun close(document: OpenDocument): Unit = mutex.withLock {
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
public class OpenDocument internal constructor(
    public val session: DocumentSession,
    public val dispatcher: CoroutineDispatcher,
    private val queue: PersistenceQueue,
    private val log: OperationLog,
    private val clock: () -> Long = ::currentTimeMillis
) {
    internal var users = 1
    private val received = MutableSharedFlow<DrawResource>(extraBufferCapacity = 64)

    public val document: DrawDocument get() = session.document

    /** The resources whose file arrived from another device (to be drawn again). */
    public val resourcesReceived: SharedFlow<DrawResource> = received

    init {
        session.addObserver(ChangePersister(queue::enqueue))
    }

    /** Suspends until the changes applied so far are stored. */
    public suspend fun flush(): Unit = queue.flush()

    /**
     * The operations of the document after [known] (a version vector), in stamp order: the ones
     * applied so far, stored or not yet.
     */
    public suspend fun operationsAfter(known: Map<String, Long>): List<Operation> {
        flush()
        return log.operationsAfter(document.id, known)
    }

    /** The last operation of every replica no longer in the log (see [OperationLog.compactOperations]). */
    public suspend fun compactedOperations(): Map<String, Long> = log.compactedOperations(document.id)

    /** The proofs kept of the operations [ids] (see [OperationLog.proofs]). */
    public suspend fun proofs(ids: Collection<OperationId>): Map<OperationId, ByteArray> = log.proofs(document.id, ids)

    /** Keeps [proofs] of operations of the document (see [OperationLog.saveProofs]). */
    public suspend fun saveProofs(proofs: Map<OperationId, ByteArray>): Unit = log.saveProofs(document.id, proofs)

    /**
     * Removes from the log the operations up to [upTo] (the ones every device working on the
     * document has: no device will ask for them); with [purge] (nothing made without seeing them is
     * on its way either), the pages and elements they removed are removed for good, but the pages
     * removed less than [TrashRetention] ago: they stay in the trash of the document.
     */
    public suspend fun compact(upTo: Map<String, Long>, purge: Boolean) {
        flush()
        val stamps = log.compactOperations(document.id, upTo)
        if (!purge) return
        val keptSince = TrashRetention.expiredBefore(clock())
        val purged = edit { session ->
            session.document.purgeHidden(pagesRemovedBefore = keptSince) { removed -> stamps[removed.replica]?.let { removed <= it } == true }
        }
        if (purged.isEmpty) return
        queue.enqueue { deleteForGood(document, purged) }
        flush()
    }

    /** The file of the resource [resourceId] (received from another device) is [path]. */
    public suspend fun resourceReceived(resourceId: String, path: String) {
        val resource = edit { session -> session.document.findResource(resourceId)?.also { it.path = path } } ?: return
        queue.enqueue { saveResource(document, resource) }
        received.emit(resource)
    }

    /** Runs [block] with the session on the thread of the document. */
    public suspend fun <T> edit(block: (DocumentSession) -> T): T = withContext(dispatcher) { block(session) }
}
