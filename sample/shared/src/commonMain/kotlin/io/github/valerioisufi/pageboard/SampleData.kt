package io.github.valerioisufi.pageboard

import io.github.valerioisufi.pageboard.canvas.render.ImageCache
import io.github.valerioisufi.pageboard.ink.InkStrokeModeller
import io.github.valerioisufi.pageboard.latex.LatexEngine
import io.github.valerioisufi.pageboard.session.EditorSessions
import io.github.valerioisufi.pageboard.store.OpenDocuments
import io.github.valerioisufi.pageboard.store.room.RoomDocumentStore
import io.github.valerioisufi.pageboard.store.room.db.createDrawDatabase

/**
 * The notes of the sample in this process (`docs/plans/instances.md`, `docs/plans/engine-instances.md`): the
 * database of the platform, opened once (Room must not open its file twice), its store, the
 * documents being changed, the editing sessions and the engines, shared by every window and kept across the activities created again.
 */
object SampleData {
    /** The store of the notes, their strokes modeled as the editor draws them. */
    val store: RoomDocumentStore by lazy { RoomDocumentStore(createDrawDatabase(), InkStrokeModeller) }

    /**
     * The documents of [store] being changed (see [OpenDocuments]), on the main thread: shared by
     * the editors and the synchronization with the other devices.
     */
    val documents: OpenDocuments by lazy { OpenDocuments(store) }

    /** The editing sessions of every window: the desktop saves them before exiting. */
    val sessions: EditorSessions = EditorSessions()

    /** The formulas of the texts, drawn by MicroTeX for the editors, the thumbnails and the export of every window. */
    val latex: LatexEngine = LatexEngine()

    /** The pixels of the images of the notes, decoded once for the renderers of every window. */
    val images: ImageCache = ImageCache()
}
