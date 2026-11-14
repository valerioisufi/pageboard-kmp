package io.github.valerioisufi.drawview

import io.github.valerioisufi.drawview.ink.InkStrokeModeller
import io.github.valerioisufi.drawview.store.OpenDocuments
import io.github.valerioisufi.drawview.store.room.RoomDocumentStore
import io.github.valerioisufi.drawview.store.room.db.createDrawDatabase

/**
 * The notes of the sample in this process (`docs/instances.md`): the database of the platform,
 * opened once (Room must not open its file twice), its store and the documents being changed,
 * shared by every window and kept across the activities created again.
 */
internal object SampleData {
    /** The store of the notes, their strokes modeled as the editor draws them. */
    val store: RoomDocumentStore by lazy { RoomDocumentStore(createDrawDatabase(), InkStrokeModeller) }

    /**
     * The documents of [store] being changed (see [OpenDocuments]), on the main thread: shared by
     * the editors and the synchronization with the other devices.
     */
    val documents: OpenDocuments by lazy { OpenDocuments(store) }
}
