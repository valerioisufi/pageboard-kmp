package io.github.valerioisufi.drawview.store.testing

import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.Stamp
import io.github.valerioisufi.drawview.model.change.OperationId
import io.github.valerioisufi.drawview.store.DocumentStore
import io.github.valerioisufi.drawview.store.space.AddDocument
import io.github.valerioisufi.drawview.store.space.AddFolder
import io.github.valerioisufi.drawview.store.space.CatalogItem
import io.github.valerioisufi.drawview.store.space.CatalogItemKind
import io.github.valerioisufi.drawview.store.space.CatalogOperation
import io.github.valerioisufi.drawview.store.space.RenameFolder
import io.github.valerioisufi.drawview.store.space.SpaceLibrary
import io.github.valerioisufi.drawview.store.space.SpaceStore
import io.github.valerioisufi.drawview.store.space.StoredSpace
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first

/**
 * What every [SpaceStore] keeps (see `docs/shared-folders.md`): the spaces, their catalogs, logs
 * and proofs, read again when the store is opened again ([reopened]: the same storage, a new
 * store), forgotten with their space while the library keeps its items; and the writes of the
 * library made by other devices, taken as they are. Run on an empty store.
 */
public suspend fun <S> checkSpaceStore(store: S, reopened: suspend () -> S) where S : DocumentStore, S : SpaceStore {
    assertEquals(emptyList(), store.spaces())
    assertTrue(store.createFolder("Course", null))
    val course = store.folders.first().single()
    val space = StoredSpace("space", course.id, sharing = "secret", readOnly = false)
    store.saveSpace(space)
    assertEquals(listOf(space), store.spaces())

    val item = CatalogItem("f", CatalogItemKind.FOLDER, inSpace = true, broughtBy = "bob", presence = Stamp(1, 0, "bob"), name = "Week 1", named = Stamp(1, 0, "bob"))
    val first = CatalogOperation(OperationId("bob", 1), Stamp(1, 0, "bob"), AddFolder("f", "Week 1", null))
    val second = CatalogOperation(OperationId("bob", 2), Stamp(3, 0, "bob"), RenameFolder("f", "Week one"))
    val ofAda = CatalogOperation(OperationId("ada", 1), Stamp(2, 0, "ada"), AddDocument("d", "f"))
    store.saveCatalogChange("space", listOf(item), first)
    store.saveCatalogChange("space", listOf(item.copy(name = "Week one", named = Stamp(3, 0, "bob"))), second)
    store.saveCatalogChange("space", listOf(CatalogItem("d", CatalogItemKind.DOCUMENT, true, "ada", Stamp(2, 0, "ada"), parent = "f", placed = Stamp(2, 0, "ada"))), ofAda)
    // Logged once: an operation kept already changes nothing
    store.saveCatalogChange("space", listOf(item.copy(name = "Other")), first)
    store.saveCatalogChange("missing", listOf(item), first)
    store.saveCatalogProofs("space", mapOf(OperationId("ada", 1) to byteArrayOf(1, 2, 3)))

    suspend fun check(store: S) {
        assertEquals(listOf(space), store.spaces())
        assertEquals(mapOf("bob" to 2L, "ada" to 1L), store.catalogVersionVector("space"))
        assertEquals(listOf(first, ofAda, second), store.catalogOperationsAfter("space", emptyMap()), "in the order of their stamps")
        assertEquals(listOf(second), store.catalogOperationsAfter("space", mapOf("bob" to 1L, "ada" to 1L)))
        assertEquals("Week one", store.catalogItems("space").single { it.id == "f" }.name)
        assertEquals("f", store.catalogItems("space").single { it.id == "d" }.parent)
        assertContentEquals(byteArrayOf(1, 2, 3), store.catalogProofs("space", listOf(OperationId("ada", 1), OperationId("bob", 1)))[OperationId("ada", 1)])
        assertEquals(1, store.catalogProofs("space", listOf(OperationId("ada", 1), OperationId("bob", 1))).size)
        assertEquals(emptyList(), store.catalogItems("missing"))
    }
    check(store)
    check(reopened())

    // The writes of other devices: taken as they are, a name taken too
    store.putFolder("f", "Week one", course.id)
    store.putFolder("g", "Week one", course.id)
    assertEquals(listOf("Week one", "Week one"), store.folders.first().filter { it.parentId == course.id }.map { it.name })
    store.putFolder("g", "Week two", "f")
    assertEquals("f", store.folders.first().single { it.id == "g" }.parentId)
    val document = store.createDocument("Notes")
    assertTrue(store.putDocumentFolder(document.id, "g"))
    assertFalse(store.putDocumentFolder("missing", "g"))
    assertEquals("g", store.documents.first().single { it.id == document.id }.folderId)
    store.putTrashed("g", true)
    assertEquals("g", store.trash.first().single().id)
    assertTrue(store.documents.first().none { it.id == document.id }, "in the trash with its folder")
    store.putTrashed("g", false)
    assertTrue(store.trash.first().isEmpty())
    assertEquals("g", store.documents.first().single { it.id == document.id }.folderId)
    store.putTrashed("missing", true)

    // Saved again: its root, sharing and role
    store.saveSpace(space.copy(sharing = "other", readOnly = true))
    assertEquals(listOf(space.copy(sharing = "other", readOnly = true)), store.spaces())
    assertEquals(mapOf("bob" to 2L, "ada" to 1L), store.catalogVersionVector("space"), "the log stays")

    // Forgotten: its catalog, log and proofs, never the library
    store.removeSpace("space")
    assertEquals(emptyList(), store.spaces())
    assertEquals(emptyList(), store.catalogItems("space"))
    assertEquals(emptyMap(), store.catalogVersionVector("space"))
    assertEquals(emptyMap(), store.catalogProofs("space", listOf(OperationId("ada", 1))))
    assertEquals(setOf(course.id, "f", "g"), store.folders.first().map { it.id }.toSet())
    assertNotNull(store.documents.first().singleOrNull { it.id == document.id })
    assertEquals(emptyList(), reopened().spaces())
}

/**
 * Two devices sharing a folder through their [SpaceLibrary]s (each on its own empty store, the
 * changes carried by hand as the synchronization would): the folder joined into a folder of the
 * other device, what each one does in it reaching the other, an item moved out kept by both, the
 * space left with the library untouched.
 */
public suspend fun <S> checkSharedFolders(ada: S, bob: S, scope: CoroutineScope) where S : DocumentStore, S : SpaceStore {
    val adaLibrary = SpaceLibrary(ada, ada, scope)
    val bobLibrary = SpaceLibrary(bob, bob, scope)
    adaLibrary.start()
    bobLibrary.start()

    // Ada's course, with a week and its notes, and a document shared on its own
    assertTrue(ada.createFolder("Course", null))
    val course = ada.folders.first().single().id
    assertTrue(ada.createFolder("Week 1", course))
    val week = ada.folders.first().single { it.name == "Week 1" }.id
    val notes = ada.createDocument("Notes", folderId = week)
    val apart = ada.createDocument("Apart", folderId = course)
    ada.setSharingSecret(apart.id, "its own")
    val space = assertNotNull(adaLibrary.share(course))
    assertNull(adaLibrary.share(week), "a space in a space")
    assertNull(adaLibrary.share(null), "the library holds a space")
    assertEquals(setOf(week, notes.id), adaLibrary.view(space.id).map { it.id }.toSet(), "not the document shared on its own")

    // Bob joins into a folder of his
    assertTrue(bob.createFolder("From Ada", null))
    val bobRoot = bob.folders.first().single().id
    assertNotNull(bobLibrary.join(space.id, bobRoot))
    suspend fun sync(from: S, fromLibrary: SpaceLibrary, to: S, toLibrary: SpaceLibrary) {
        fromLibrary.reconcile()
        val missing = from.catalogOperationsAfter(space.id, toLibrary.versionVector(space.id))
        toLibrary.receive(space.id, missing)
        // The documents the space holds that the other device has not got yet (their transfer is the synchronization's)
        for (entry in toLibrary.view(space.id).filter { it.kind == CatalogItemKind.DOCUMENT }) {
            if (to.documents.first().any { it.id == entry.id } || to.trash.first().any { it.id == entry.id }) continue
            val document = from.loadDocument(entry.id) ?: continue
            to.insertDocument(DrawDocument(document.id, document.title), toLibrary.folderOf(entry.id)!!.getOrNull())
        }
        toLibrary.reconcile()
    }
    sync(ada, adaLibrary, bob, bobLibrary)
    val bobWeek = bob.folders.first().single { it.id == week }
    assertEquals("Week 1" to bobRoot, bobWeek.name to bobWeek.parentId, "the same folder, under the root of Bob")
    assertEquals(week, bob.documents.first().single { it.id == notes.id }.folderId)
    assertTrue(bob.documents.first().none { it.id == apart.id })

    // Bob renames the week, makes a folder in it and puts the notes in the trash
    assertTrue(bob.renameFolder(week, "Week one"))
    assertTrue(bob.createFolder("Exercises", week))
    val exercises = bob.folders.first().single { it.name == "Exercises" }.id
    assertTrue(bob.trashDocument(notes.id))
    sync(bob, bobLibrary, ada, adaLibrary)
    assertEquals("Week one", ada.folders.first().single { it.id == week }.name)
    assertEquals(week, ada.folders.first().single { it.id == exercises }.parentId)
    assertEquals(listOf(notes.id), ada.trash.first().map { it.id })
    assertTrue(bob.documents.first().none { it.id == notes.id } && bob.trash.first().any { it.id == notes.id }, "still in Bob's trash")

    // Ada restores the notes and moves the exercises out of the course: Bob keeps them, out of his root
    assertNotNull(ada.restore(notes.id))
    assertTrue(ada.moveFolder(exercises, null))
    sync(ada, adaLibrary, bob, bobLibrary)
    assertEquals(week, bob.documents.first().single { it.id == notes.id }.folderId)
    assertNull(bob.folders.first().single { it.id == exercises }.parentId, "out of the root of the space: where the root is")
    assertTrue(adaLibrary.view(space.id).none { it.id == exercises })
    sync(bob, bobLibrary, ada, adaLibrary)
    assertNull(ada.folders.first().single { it.id == exercises }.parentId, "Ada's move stays")

    // A document deleted for good on Bob: the space keeps it for Ada
    assertTrue(bob.deleteDocument(notes.id))
    sync(bob, bobLibrary, ada, adaLibrary)
    assertTrue(adaLibrary.view(space.id).any { it.id == notes.id })
    assertEquals(week, ada.documents.first().single { it.id == notes.id }.folderId)

    // Leaving: the library of each keeps what it holds
    val adaFolders = ada.folders.first().map { it.id }.toSet()
    val bobFolders = bob.folders.first().map { it.id }.toSet()
    bobLibrary.leave(space.id)
    adaLibrary.leave(space.id)
    assertEquals(emptyList(), bob.spaces())
    assertEquals(adaFolders, ada.folders.first().map { it.id }.toSet())
    assertEquals(bobFolders, bob.folders.first().map { it.id }.toSet())
    assertTrue(ada.documents.first().any { it.id == notes.id })
    adaLibrary.stop()
    bobLibrary.stop()
}
