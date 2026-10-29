package io.github.valerioisufi.drawview.sync

import io.github.valerioisufi.drawview.store.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.store.KeyValueDocumentStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class StoreSharedDocumentsTest {

    @Test
    fun theSharingOfADocumentInTheTrashIsStored() = runTest {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = backgroundScope)
        val notes = store.createDocument("Notes")
        val shared = StoreSharedDocuments(store)
        shared.save(notes.id, SharingState(owner = "ada"), readOnly = false)
        store.trashDocument(notes.id)

        // A change of its sharing while it is in the trash: stored, not waiting for the document
        shared.save(notes.id, SharingState(owner = "bob"), readOnly = true)
        assertEquals("bob", StoreSharedDocuments(store).stateOf(notes.id)?.owner)
        assertEquals(true, store.isReadOnly(notes.id))

        // A document still to come: waiting in memory only
        shared.save("joined", SharingState(owner = "cy"), readOnly = false)
        assertEquals("cy", shared.stateOf("joined")?.owner)
        assertNull(StoreSharedDocuments(store).stateOf("joined"))
    }
}
