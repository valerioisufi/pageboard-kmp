package io.github.valerioisufi.pencilnotes

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.runDesktopComposeUiTest
import java.util.Locale
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/** A document in a window of its own has the editor of the library (`docs/document-window.md`). */
@OptIn(ExperimentalTestApi::class)
class DocumentWindowTest {

    @Test
    fun aDocumentInItsOwnWindowHasTheLecturesAndTheSharing() = runDesktopComposeUiTest(width = 1400, height = 900) {
        // The notes of the tests only (drawview.test.data), never the ones of the user
        val dataDirectory = System.getProperty("drawview.dataDir").orEmpty()
        check("/build/test-data/" in dataDirectory.replace(java.io.File.separatorChar, '/')) { "Not the data directory of the tests: $dataDirectory" }
        Locale.setDefault(Locale.US)
        val document = runBlocking { PencilNotesData.store.createDocument("Lecture 1") }
        setContent {
            PencilNotesDocumentWindow(documentId = document.id, onClose = {})
        }
        // The button of the lecture assistant and the action Share, as in the editor of the library
        for (label in listOf("Lecture assistant", "Share")) {
            waitUntil(timeoutMillis = 20_000) { onAllNodes(hasContentDescription(label) or hasText(label)).fetchSemanticsNodes().isNotEmpty() }
        }
    }
}
