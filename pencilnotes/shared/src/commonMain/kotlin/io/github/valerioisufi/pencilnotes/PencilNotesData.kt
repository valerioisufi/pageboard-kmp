package io.github.valerioisufi.pencilnotes

import io.github.valerioisufi.drawview.ink.InkStrokeModeller
import io.github.valerioisufi.drawview.session.EditorSessions
import io.github.valerioisufi.drawview.store.OpenDocuments
import io.github.valerioisufi.drawview.store.room.RoomDocumentStore
import io.github.valerioisufi.drawview.store.room.db.createDrawDatabase

/**
 * The notes of PencilNotes in this process (`docs/plans/instances.md`): the database of the platform,
 * opened once (Room must not open its file twice), its store, the documents being changed and the
 * editing sessions, shared by every window and kept across the activities created again (what
 * works on them is in [PencilNotesServices]).
 */
object PencilNotesData {
    /** The store of the notes, their strokes modeled as the editor draws them. */
    val store: RoomDocumentStore by lazy { RoomDocumentStore(createDrawDatabase(), InkStrokeModeller) }

    /**
     * The documents of [store] being changed (see [OpenDocuments]), on the main thread: shared by
     * the editors and the synchronization with the other devices.
     */
    val documents: OpenDocuments by lazy { OpenDocuments(store) }

    /** The editing sessions of every window: the desktop saves them before exiting. */
    val sessions: EditorSessions = EditorSessions()
}
