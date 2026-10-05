package io.github.valerioisufi.drawview.ui.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.valerioisufi.drawview.core.controller.DrawPreferences
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.core.model.DrawDocument
import io.github.valerioisufi.drawview.core.util.SyncLock
import io.github.valerioisufi.drawview.core.util.syncLock
import io.github.valerioisufi.drawview.data.DocumentStore
import io.github.valerioisufi.drawview.pdf.PdfiumEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The editing session of an open document.
 */
class EditorSession internal constructor(
    val documentId: String,
    internal val store: DocumentStore
) {
    /** `null` while the document is being loaded. */
    var controller: DrawViewController? by mutableStateOf(null)
        internal set

    /**
     * Scope of the persistence queue: it outlives the UI, so that the pending changes are written
     * while the editor is being closed.
     */
    internal val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Writes every pending change of the session. */
    suspend fun flush() {
        controller?.flushPersistence()
        store.flush()
    }
}

/**
 * The open document (if any) of an application: loads it, creates its editor and writes its
 * changes when it is closed. Survives the configuration changes of Android (e.g. rotations).
 */
class DocumentEditorViewModel(val store: DocumentStore) : ViewModel() {

    var session: EditorSession? by mutableStateOf(null)
        private set

    /** Opens the document [documentId] (closing the current one). */
    fun openDocument(documentId: String) {
        if (session?.documentId == documentId) return
        closeSession()
        val newSession = EditorSession(documentId, store)
        session = newSession
        DrawViewSessions.register(newSession)
        viewModelScope.launch {
            val document = try {
                store.loadDocument(documentId)
            } catch (e: Throwable) {
                println("DrawView: cannot load the document $documentId: ${e.message}")
                null
            }
            if (document == null) {
                if (session === newSession) closeSession()
                return@launch
            }
            store.updateLastOpened(documentId)
            attach(newSession, document, store.preferences.first())
        }
    }

    /** Creates a new document (one A4 page, as in the original app) in [folderId] and opens it. */
    fun createAndOpenDocument(name: String, folderId: String? = null) {
        viewModelScope.launch {
            val document = store.createDocument(name, folderId = folderId)
            closeSession()
            val newSession = EditorSession(document.id, store)
            session = newSession
            DrawViewSessions.register(newSession)
            attach(newSession, document, store.preferences.first())
        }
    }

    private fun attach(session: EditorSession, document: DrawDocument, preferences: DrawPreferences) {
        if (this.session !== session) return
        session.controller = DrawViewController(
            document = document,
            coroutineScope = viewModelScope,
            persistence = store.persistence,
            persistenceScope = session.persistenceScope
        ).apply {
            applyPreferences(preferences)
            // PDF pages rendering (PDFium on Android, iOS and the desktop)
            tileManager.tileRenderer.pdfAdapter = PdfiumEngine.createTileAdapter()
        }
    }

    /** Closes the open document, writing its pending changes. */
    fun closeSession() {
        val current = session ?: return
        session = null
        DrawViewSessions.close(current)
    }

    override fun onCleared() {
        closeSession()
    }
}

/**
 * The editing sessions of the process: closing a session writes its pending changes in the
 * background, [flushAll] waits for every write (e.g. before the desktop application exits).
 */
object DrawViewSessions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = SyncLock()
    private val open = mutableSetOf<EditorSession>()
    private val closing = mutableSetOf<Job>()

    internal fun register(session: EditorSession) {
        syncLock(lock) { open.add(session) }
    }

    internal fun close(session: EditorSession) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                session.flush()
                session.store.closeDocument(session.documentId)
            } catch (e: Throwable) {
                println("DrawView: cannot save the document ${session.documentId}: ${e.message}")
            } finally {
                session.controller?.dispose()
                session.persistenceScope.cancel()
            }
        }
        syncLock(lock) {
            open.remove(session)
            closing.add(job)
        }
        job.invokeOnCompletion { syncLock(lock) { closing.remove(job) } }
        job.start()
    }

    /** Writes the pending changes of every session (open or being closed). */
    suspend fun flushAll() {
        val (sessions, jobs) = syncLock(lock) { open.toList() to closing.toList() }
        for (session in sessions) {
            try {
                session.flush()
            } catch (e: Throwable) {
                println("DrawView: cannot save the document ${session.documentId}: ${e.message}")
            }
        }
        jobs.forEach { it.join() }
    }
}
