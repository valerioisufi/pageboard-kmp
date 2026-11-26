package io.github.valerioisufi.pageboard.store

import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.model.DocumentResource
import io.github.valerioisufi.pageboard.platform.PlatformFiles
import io.github.valerioisufi.pageboard.platform.newResourcePath
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

    @Test
    fun resourcesInsideResourcesDirectoryAreStoredRelativeAndRestored() = runTest {
        val pdfPath = newResourcePath("kv_rel", "pdf")
        val relativeName = PlatformFiles.name(pdfPath)
        assertTrue(PlatformFiles.writeBytes(pdfPath, byteArrayOf(7, 8, 9)))
        try {
            val store = KeyValueDocumentStore(storage, scope = backgroundScope)
            val a = store.createDocument("A")
            val b = store.createDocument("B")
            val resourceA = DocumentResource(id = "pdf-a", type = DocumentResource.ResourceType.PDF, path = pdfPath, hash = "kv-pdf-hash")
            val resourceB = DocumentResource(id = "pdf-b", type = DocumentResource.ResourceType.PDF, path = pdfPath, hash = "kv-pdf-hash")
            a.addResource(resourceA)
            store.saveResource(a, resourceA)
            b.addResource(resourceB)
            store.saveResource(b, resourceB)
            store.flush()

            val storedJson = assertNotNull(storage.get("pageboard.document.${a.id}"))
            assertTrue("\"uri\":\"$relativeName\"" in storedJson, "stored JSON keeps relative URI: $storedJson")
            assertFalse(PlatformFiles.resourcesDirectory() in storedJson, "stored JSON omits resourcesDirectory prefix")
            assertTrue("pageboard.file.$relativeName" in storage.keys)

            // Deleting the physical file and reloading restores it at the resolved path
            assertTrue(PlatformFiles.delete(pdfPath))
            assertFalse(PlatformFiles.exists(pdfPath))

            val reloaded = KeyValueDocumentStore(storage, scope = backgroundScope)
            val loaded = assertNotNull(reloaded.loadDocument(a.id))
            assertEquals(pdfPath, loaded.findResource("pdf-a")?.path)
            assertEquals(pdfPath, reloaded.fileWithContent("kv-pdf-hash"))
            assertTrue(PlatformFiles.exists(pdfPath))

            // Reference counting across A and B
            assertTrue(reloaded.deleteDocument(a.id))
            assertTrue(PlatformFiles.exists(pdfPath), "B still references the shared relative URI")
            assertTrue("pageboard.file.$relativeName" in storage.keys)
            assertTrue(reloaded.deleteDocument(b.id))
            assertFalse(PlatformFiles.exists(pdfPath), "last referencing document deleted")
            assertFalse("pageboard.file.$relativeName" in storage.keys)
        } finally {
            PlatformFiles.delete(pdfPath)
        }
    }
}
