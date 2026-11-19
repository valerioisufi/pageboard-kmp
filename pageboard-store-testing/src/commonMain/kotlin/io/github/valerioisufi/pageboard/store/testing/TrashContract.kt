package io.github.valerioisufi.pageboard.store.testing

import io.github.valerioisufi.pageboard.store.DocumentStore
import io.github.valerioisufi.pageboard.store.TrashedItem
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first

/**
 * The trash every [DocumentStore] must keep (see `DocumentCatalog.trash`): run by the tests of each
 * store, of DrawView or of an application, on an empty store.
 */
public suspend fun checkTrash(store: DocumentStore) {
    suspend fun documentIds() = store.documents.first().map { it.id }.toSet()
    suspend fun item(id: String) = store.trash.first().single { it.id == id }

    assertTrue(store.createFolder("Math", null))
    val math = store.folders.first().single()
    assertTrue(store.createFolder("Algebra", math.id))
    val algebra = store.folders.first().single { it.name == "Algebra" }
    val notes = store.createDocument("Notes")
    val rings = store.createDocument("Rings", folderId = math.id)
    val groups = store.createDocument("Groups", folderId = algebra.id)
    val fields = store.createDocument("Fields", folderId = algebra.id)

    // A document in the trash is out of the library, kept as it is
    assertTrue(store.trashDocument(notes.id))
    assertFalse(store.trashDocument(notes.id), "already in the trash")
    assertFalse(notes.id in documentIds())
    val trashed = item(notes.id)
    assertEquals(TrashedItem.Kind.DOCUMENT, trashed.kind)
    assertEquals("Notes", trashed.name)
    assertNull(trashed.folderId)
    assertEquals(setOf(notes.id), trashed.documentIds)
    assertEquals(1, assertNotNull(store.loadDocument(notes.id), "its content kept").pages.size)

    // Its name is free meanwhile, and taken again on restore
    val other = store.createDocument("Other")
    assertTrue(store.isDocumentNameAvailable(other.id, "Notes"))
    val newNotes = store.createDocument("Notes")
    assertEquals("Notes (2)", store.restore(notes.id))
    assertTrue(notes.id in documentIds())
    assertEquals("Notes (2)", store.documents.first().single { it.id == notes.id }.name)
    assertTrue(store.trash.first().isEmpty())
    assertNull(store.restore(notes.id), "not in the trash")

    // A folder is one item with its content; what went to the trash before it stays an item of its own
    assertTrue(store.trashDocument(rings.id))
    assertTrue(store.trashDocument(fields.id))
    assertTrue(store.trashFolder(math.id))
    assertFalse(store.trashFolder(algebra.id), "its parent is in the trash")
    assertFalse(store.trashDocument(groups.id), "its folder is in the trash")
    assertTrue(store.folders.first().isEmpty())
    assertEquals(setOf(notes.id, newNotes.id, other.id), documentIds())
    assertEquals(setOf(math.id, rings.id, fields.id), store.trash.first().map { it.id }.toSet())
    val folder = item(math.id)
    assertEquals(TrashedItem.Kind.FOLDER, folder.kind)
    assertEquals(setOf(groups.id), folder.documentIds)
    assertEquals("Math", item(rings.id).folderName)
    assertEquals("Algebra", item(fields.id).folderName)
    assertFalse(store.moveDocument(other.id, math.id), "not into a folder in the trash")
    assertFalse(store.createFolder("Inside", math.id), "not into a folder in the trash")

    // Restored while its folder is in the trash: at the root
    assertEquals("Rings", store.restore(rings.id))
    assertNull(store.documents.first().single { it.id == rings.id }.folderId)

    // Deleting the folder for good keeps what is in the trash on its own, at its root
    assertTrue(store.deleteFolder(math.id))
    assertNull(store.loadDocument(groups.id))
    assertEquals(listOf(fields.id), store.trash.first().map { it.id })
    assertNull(item(fields.id).folderId)
    assertEquals("Fields", store.restore(fields.id))
    assertNull(store.documents.first().single { it.id == fields.id }.folderId)

    // A folder restored under a name taken meanwhile, with its content
    assertTrue(store.createFolder("Physics", null))
    val physics = store.folders.first().single { it.name == "Physics" }
    val waves = store.createDocument("Waves", folderId = physics.id)
    assertTrue(store.trashFolder(physics.id))
    assertFalse(waves.id in documentIds())
    assertTrue(store.createFolder("Physics", null), "its name is free meanwhile")
    assertEquals("Physics (2)", store.restore(physics.id))
    assertEquals(setOf("Physics", "Physics (2)"), store.folders.first().map { it.name }.toSet())
    assertEquals(physics.id, store.documents.first().single { it.id == waves.id }.folderId)

    // Deleted for good once older than the date given
    assertTrue(store.trashDocument(other.id))
    assertTrue(store.trashFolder(physics.id))
    store.purgeTrash(before = 0L)
    assertEquals(2, store.trash.first().size, "nothing that old")
    store.purgeTrash(before = Long.MAX_VALUE)
    assertTrue(store.trash.first().isEmpty())
    assertNull(store.loadDocument(other.id))
    assertNull(store.loadDocument(waves.id))
    assertEquals(setOf(notes.id, newNotes.id, rings.id, fields.id), documentIds())
}
