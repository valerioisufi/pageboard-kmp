package io.github.valerioisufi.drawview.explorer

import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.model.change.TextData
import io.github.valerioisufi.drawview.model.newElementId
import io.github.valerioisufi.drawview.store.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.store.KeyValueDocumentStore
import io.github.valerioisufi.drawview.store.OpenDocuments
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest

class DocumentThumbnailsTest {

    @Test
    fun theFirstPageIsDrawnKeptAndDrawnAgainWhenItChanges() = runTest {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = backgroundScope)
        val document = store.createDocument("Note", background = PageBackground.Ruled())
        val documents = OpenDocuments(store, dispatcher = Dispatchers.Unconfined)
        val directory = Files.createTempDirectory("thumbnails").toFile()
        try {
            val thumbnails = DocumentThumbnails(documents, textAdapter = null, directory = directory.path)

            val first = assertNotNull(thumbnails.of(document.id, modifiedAt = 1L, widthPx = 240))
            assertEquals(240, first.width)
            // A4: the height of the page
            assertEquals(340, first.height, "${first.width}x${first.height}")
            assertEquals(0, thumbnails.rendering)
            // Kept in memory, and on disk with its key
            assertSame(first, thumbnails.of(document.id, modifiedAt = 1L, widthPx = 120))
            assertTrue(File(directory, "${document.id}.png").exists())
            val fingerprint = assertNotNull(store.pageFingerprint(document.id, 0))
            assertEquals("1:240:$fingerprint", key(directory, document.id))

            // Another library reads it from the disk
            val fromDisk = assertNotNull(DocumentThumbnails(documents, null, directory.path).of(document.id, 1L, 240))
            assertEquals(240, fromDisk.width)

            // The document changed, but not its first page: kept, dated again
            assertSame(first, thumbnails.of(document.id, modifiedAt = 2L, widthPx = 240))
            assertEquals("2:240:$fingerprint", key(directory, document.id))
            val fromAnotherLibrary = DocumentThumbnails(documents, null, directory.path)
            assertNotNull(fromAnotherLibrary.of(document.id, modifiedAt = 3L, widthPx = 240))
            assertEquals("3:240:$fingerprint", key(directory, document.id), "dated again from the disk")

            // Its first page changed, or wanted larger: drawn again
            val open = assertNotNull(documents.open(document.id))
            open.edit { session ->
                val page = session.document.pages.first()
                session.submit(AddElements(page.id, listOf(TextData(newElementId(), "changed", x = 20f, y = 20f, width = 60f, color = 0xFF000000.toInt(), fontSize = 5f))))
            }
            documents.close(open)
            val changed = assertNotNull(thumbnails.of(document.id, modifiedAt = 4L, widthPx = 240))
            assertNotSame(first, changed)
            val changedFingerprint = assertNotNull(store.pageFingerprint(document.id, 0))
            assertNotEquals(fingerprint, changedFingerprint)
            assertEquals(480, assertNotNull(thumbnails.of(document.id, modifiedAt = 4L, widthPx = 480)).width)
            assertEquals("4:480:$changedFingerprint", key(directory, document.id))

            // A key of the thumbnails before their fingerprints: drawn again once the document changes
            File(directory, "${document.id}.key").writeText("4:480")
            val fromOldKey = DocumentThumbnails(documents, null, directory.path)
            assertEquals(480, assertNotNull(fromOldKey.of(document.id, modifiedAt = 4L, widthPx = 480)).width)
            assertNotNull(fromOldKey.of(document.id, modifiedAt = 5L, widthPx = 480))
            assertEquals("5:480:$changedFingerprint", key(directory, document.id))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun key(directory: File, documentId: String) = File(directory, "$documentId.key").readText()
}
