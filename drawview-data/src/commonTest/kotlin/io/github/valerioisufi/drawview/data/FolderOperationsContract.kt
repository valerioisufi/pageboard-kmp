package io.github.valerioisufi.drawview.data

import kotlinx.coroutines.flow.first
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Folder operations every [DocumentStore] must support (run by the tests of each store).
 */
internal suspend fun checkFolderOperations(store: DocumentStore) {
    assertTrue(store.createFolder("Math", null))
    assertFalse(store.createFolder("Math", null), "folder names are unique in a parent")
    val math = store.folders.first().single()
    assertTrue(store.createFolder("Algebra", math.id))
    val algebra = store.folders.first().single { it.name == "Algebra" }
    assertEquals(math.id, algebra.parentId)

    val inRoot = store.createDocument("Notes")
    val inAlgebra = store.createDocument("Groups", folderId = algebra.id)
    assertEquals(algebra.id, store.documents.first().single { it.id == inAlgebra.dbId }.folderId)

    assertFalse(store.moveFolder(math.id, math.id), "a folder cannot be moved into itself")
    assertFalse(store.moveFolder(math.id, algebra.id), "a folder cannot be moved into a sub-folder")
    assertTrue(store.moveDocument(inRoot.dbId, math.id))
    assertEquals(math.id, store.documents.first().single { it.id == inRoot.dbId }.folderId)
    assertTrue(store.moveFolder(algebra.id, null))
    assertNull(store.folders.first().single { it.id == algebra.id }.parentId)
    assertTrue(store.renameFolder(algebra.id, "Linear algebra"))
    assertTrue(store.moveFolder(algebra.id, math.id))
    assertEquals(setOf("Math", "Linear algebra"), store.folders.first().map { it.name }.toSet())

    // A folder is deleted with its whole content
    assertTrue(store.deleteFolder(math.id))
    assertTrue(store.folders.first().isEmpty())
    assertTrue(store.documents.first().isEmpty())
    assertNull(store.loadDocument(inAlgebra.dbId))
}
