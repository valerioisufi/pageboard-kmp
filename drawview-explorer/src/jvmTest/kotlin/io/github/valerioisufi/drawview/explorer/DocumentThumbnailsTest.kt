package io.github.valerioisufi.drawview.explorer

import io.github.valerioisufi.drawview.core.model.PageBackground
import io.github.valerioisufi.drawview.data.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.data.KeyValueDocumentStore
import io.github.valerioisufi.drawview.data.OpenDocuments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DocumentThumbnailsTest {

    @Test
    fun theFirstPageIsDrawnKeptAndDrawnAgainWhenTheDocumentChanges() = runTest {
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
            assertEquals("1:240", File(directory, "${document.id}.key").readText())

            // Another library reads it from the disk
            val fromDisk = assertNotNull(DocumentThumbnails(documents, null, directory.path).of(document.id, 1L, 240))
            assertEquals(240, fromDisk.width)

            // Changed, or wanted larger: drawn again
            val changed = assertNotNull(thumbnails.of(document.id, modifiedAt = 2L, widthPx = 240))
            assertNotSame(first, changed)
            assertEquals(480, assertNotNull(thumbnails.of(document.id, modifiedAt = 2L, widthPx = 480)).width)
            assertEquals("2:480", File(directory, "${document.id}.key").readText())
        } finally {
            directory.deleteRecursively()
        }
    }
}
