package io.github.valerioisufi.pageboard.store

import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.model.DocumentResource
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * The files of the resources are kept once for every document (the same content, or an element
 * pasted from another document): deleting a document keeps the files the others point at.
 */
class SharedResourceFilesTest {

    private val directory: File = Files.createTempDirectory("pageboard-shared-files").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun file(name: String): File = File(directory, name).apply { writeBytes(byteArrayOf(1, 2, 3)) }

    private suspend fun KeyValueDocumentStore.addImage(document: Document, file: File) {
        val resource = DocumentResource(id = "image-${document.id}", type = DocumentResource.ResourceType.IMAGE, path = file.absolutePath, hash = "hash")
        document.addResource(resource)
        saveResource(document, resource)
    }

    private val storage = InMemoryKeyValueStorage()

    private fun hasStoredCopy(file: File) = storage.keys.any { it.endsWith("file." + file.absolutePath) }

    @Test
    fun aDocumentDeletedKeepsTheFilesOfTheOthers() = runTest {
        val store = KeyValueDocumentStore(storage, scope = backgroundScope)
        val shared = file("shared.png")
        val own = file("own.png")
        val a = store.createDocument("A")
        val b = store.createDocument("B")
        store.addImage(a, shared)
        store.addImage(b, shared)
        store.addImage(a, own)
        store.flush()

        assertTrue(store.deleteDocument(a.id))
        assertTrue(shared.exists(), "B still points at the file")
        assertTrue(hasStoredCopy(shared))
        assertFalse(own.exists(), "no other document points at it")
        assertFalse(hasStoredCopy(own))

        // Read from the storage, the document not open
        val reloaded = KeyValueDocumentStore(storage, scope = backgroundScope)
        assertTrue(reloaded.deleteDocument(b.id))
        assertFalse(shared.exists(), "the last document pointing at it is deleted")
        assertFalse(hasStoredCopy(shared))
    }

    @Test
    fun aDocumentDeletedKeepsItsFilesWhileAnotherCannotBeRead() = runTest {
        val store = KeyValueDocumentStore(storage, scope = backgroundScope)
        val shared = file("shared.png")
        val a = store.createDocument("A")
        val b = store.createDocument("B")
        store.addImage(a, shared)
        store.addImage(b, shared)
        store.flush()
        // B unreadable (written by a newer version, or damaged): the files it points at are not known
        storage.set("pageboard.document." + b.id, "{ not a document")

        assertTrue(KeyValueDocumentStore(storage, scope = backgroundScope).deleteDocument(a.id))
        assertTrue(shared.exists(), "B may point at the file")
        assertTrue(hasStoredCopy(shared))
    }

    @Test
    fun aFolderDeletedKeepsTheFilesOfTheDocumentsOutsideIt() = runTest {
        val store = KeyValueDocumentStore(storage, scope = backgroundScope)
        assertTrue(store.createFolder("Math", null))
        val folder = store.folders.first().single()
        val shared = file("shared.png")
        val inFolder = file("in-folder.png")
        val first = store.createDocument("First", folderId = folder.id)
        val second = store.createDocument("Second", folderId = folder.id)
        val outside = store.createDocument("Outside")
        store.addImage(first, inFolder)
        store.addImage(second, inFolder)
        store.addImage(second, shared)
        store.addImage(outside, shared)
        store.flush()

        assertTrue(KeyValueDocumentStore(storage, scope = backgroundScope).deleteFolder(folder.id))
        assertFalse(inFolder.exists(), "every document pointing at it is deleted with the folder")
        assertTrue(shared.exists(), "a document outside the folder points at it")
        assertTrue(hasStoredCopy(shared))
    }
}
