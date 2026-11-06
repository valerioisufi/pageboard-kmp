package io.github.valerioisufi.drawview.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.valerioisufi.drawview.canvas.controller.DrawPreferences
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.canvas.controller.EditorClipboard
import io.github.valerioisufi.drawview.canvas.render.DrawViewEngines
import io.github.valerioisufi.drawview.platform.SyncLock
import io.github.valerioisufi.drawview.platform.syncLock
import io.github.valerioisufi.drawview.store.DocumentStore
import io.github.valerioisufi.drawview.store.OpenDocument
import io.github.valerioisufi.drawview.store.OpenDocuments
import io.github.valerioisufi.drawview.store.PreferenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The editing session of an open document.
 */
public class EditorSession internal constructor(
    public val documentId: String,
    internal val documents: OpenDocuments
) {
    internal val store: DocumentStore get() = documents.store

    /** `null` while the document is being loaded. */
    public var controller: DrawViewController? by mutableStateOf(null)
        internal set

    /** The document being changed (`null` while it is being loaded), shared with the synchronization. */
    public var document: OpenDocument? = null
        internal set

    /**
     * Scope of the persistence queue: it outlives the UI, so that the pending changes are written
     * while the editor is being closed.
     */
    internal val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Saves the text being written in the document, its box staying open (see
     * `TextEditorManager.saveWriting`), so that it survives the process; on the main thread.
     */
    public fun saveWriting() {
        controller?.textEditorManager?.saveWriting()
    }

    /** Writes every pending change of the session. */
    public suspend fun flush() {
        controller?.flushPersistence()
        document?.flush()
        store.flush()
    }
}

/**
 * The open document (if any) of an application: opens it through [documents] (where the
 * synchronization with the other devices changes it too), creates its editor and writes its
 * changes when it is closed. Survives the configuration changes of Android (e.g. rotations).
 */
public class DocumentEditorViewModel(
    public val documents: OpenDocuments,
    /** The engines of the editors (PDF pages, formulas). */
    public val engines: DrawViewEngines = DrawViewEngines.None,
    /** Where the preferences of the user are kept (by default, in the store when it keeps them). */
    public val preferences: PreferenceStore? = documents.store as? PreferenceStore,
    /**
     * What is copied and pasted in its editors: the one of the application, so that what is copied
     * in a document is pasted in another one, in any window (by default, the editors of this model only).
     */
    public val clipboard: EditorClipboard = EditorClipboard()
) : ViewModel() {
    private val store: DocumentStore get() = documents.store

    public var session: EditorSession? by mutableStateOf(null)
        private set

    /** Opens the document [documentId] (closing the current one). */
    public fun openDocument(documentId: String) {
        openDocument(documentId) {}
    }

    /**
     * Opens the document [documentId] (closing the current one); [then] is given its editor once
     * open (e.g. to jump to a page and its area).
     */
    public fun openDocument(documentId: String, then: (DrawViewController) -> Unit) {
        val current = session
        if (current != null && current.documentId == documentId) {
            current.controller?.let(then)
            return
        }
        closeSession()
        val newSession = EditorSession(documentId, documents)
        session = newSession
        DrawViewSessions.register(newSession)
        viewModelScope.launch {
            load(newSession)
            if (this@DocumentEditorViewModel.session === newSession) {
                newSession.controller?.let(then)
            }
        }
    }

    /**
     * Creates a new document (one A4 page, as in the original app) in [folderId] and opens it;
     * [then] is given its editor once open (e.g. to import the files it was made from).
     */
    public fun createAndOpenDocument(name: String, folderId: String? = null, then: (DrawViewController) -> Unit = {}) {
        viewModelScope.launch {
            val document = store.createDocument(name, folderId = folderId)
            closeSession()
            val newSession = EditorSession(document.id, documents)
            session = newSession
            DrawViewSessions.register(newSession)
            load(newSession)
            newSession.controller?.let(then)
        }
    }

    /** Opens the document of [session] and creates its editor (unless closed meanwhile). */
    private suspend fun load(session: EditorSession) {
        val open = try {
            documents.open(session.documentId)
        } catch (e: Throwable) {
            println("DrawView: cannot load the document ${session.documentId}: ${e.message}")
            null
        }
        if (open == null) {
            if (this.session === session) closeSession()
            return
        }
        // Closed meanwhile: the editing session ends without it
        session.document = open
        if (this.session !== session) {
            DrawViewSessions.close(session)
            return
        }
        store.updateLastOpened(session.documentId)
        val kept = preferences?.preference(EDITOR_PREFERENCES)?.let(DrawPreferences::decode)
        if (this.session !== session) return
        val controller = DrawViewController(
            document = open.document,
            coroutineScope = viewModelScope,
            persistence = store.persistence,
            persistenceScope = session.persistenceScope,
            sharedSession = open.session,
            engines = engines,
            clipboard = clipboard
        ).apply {
            kept?.let(::applyPreferences)
        }
        session.controller = controller
        // The preferences of the user kept as they change
        preferences?.let { store ->
            viewModelScope.launch {
                controller.preferences.drop(1).collect { store.setPreference(EDITOR_PREFERENCES, it.encode()) }
            }
        }
        // The files arriving from the other devices are drawn
        viewModelScope.launch {
            open.resourcesReceived.collect { if (session.controller === controller) controller.onResourceAvailable(it) }
        }
    }

    /** Closes the open document, writing its pending changes (the text being written too). */
    public fun closeSession() {
        val current = session ?: return
        // The box being written closed and saved, before the editor goes
        current.controller?.textEditorManager?.finishEditing()
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
public object DrawViewSessions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = SyncLock()
    private val open = mutableSetOf<EditorSession>()
    private val closing = mutableSetOf<Job>()

    public fun find(documentId: String): EditorSession? = syncLock(lock) {
        open.firstOrNull { it.documentId == documentId }
    }

    internal fun register(session: EditorSession) {
        syncLock(lock) { open.add(session) }
    }

    internal fun close(session: EditorSession) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                session.flush()
                session.controller?.dispose()
                session.document?.let { session.documents.close(it) }
                session.document = null
            } catch (e: Throwable) {
                println("DrawView: cannot save the document ${session.documentId}: ${e.message}")
            } finally {
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

    /**
     * Saves the texts being written in the open sessions (see [EditorSession.saveWriting]), on the
     * main thread: e.g. before [flushAll] when the application exits.
     */
    public fun saveWriting() {
        syncLock(lock) { open.toList() }.forEach { it.saveWriting() }
    }

    /** Writes the pending changes of every session (open or being closed). */
    public suspend fun flushAll() {
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

/** The key of the preferences of the editor in a [PreferenceStore]. */
private const val EDITOR_PREFERENCES = "drawview.editor"
