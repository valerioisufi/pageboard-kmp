package io.github.valerioisufi.pageboard

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.valerioisufi.pageboard.canvas.controller.EditorClipboard
import io.github.valerioisufi.pageboard.canvas.observe
import io.github.valerioisufi.pageboard.canvas.render.PageboardEngines
import io.github.valerioisufi.pageboard.canvas.render.ElementRenderers
import io.github.valerioisufi.pageboard.explorer.DocumentLibrary
import io.github.valerioisufi.pageboard.explorer.DocumentThumbnails
import io.github.valerioisufi.pageboard.explorer.rememberDocumentThumbnails
import io.github.valerioisufi.pageboard.pdf.PdfiumEngine
import io.github.valerioisufi.pageboard.session.DocumentEditorViewModel
import io.github.valerioisufi.pageboard.session.EditorSessionScreen
import io.github.valerioisufi.pageboard.ui.command.CommandHost
import io.github.valerioisufi.pageboard.ui.command.CommandRoot
import io.github.valerioisufi.pageboard.ui.generated.resources.Res
import io.github.valerioisufi.pageboard.ui.generated.resources.app_name
import io.github.valerioisufi.pageboard.ui.theme.PageboardTheme
import org.jetbrains.compose.resources.stringResource

/**
 * What is copied and pasted in the editors of the sample: one for the process, shared by the editors
 * of all its documents and windows, so that what is copied in a document is pasted in another one
 * (it outlives the activities of Android, recreated on a rotation).
 */
private val editorClipboard = EditorClipboard()

/**
 * Pageboard sample application: the document library and the editor of the open document, with
 * the Pageboard theme, and an element type of its own (the shapes, see [ShapeType]).
 *
 * @param commandHost the commands of the screen shown: the desktop gives them to the menu bar and
 *   to the shortcuts of its window.
 * @param onOpenDocumentInNewWindow opens the document in an independent desktop window when provided.
 */
@Composable
fun App(
    commandHost: CommandHost = remember { CommandHost() },
    onOpenDocumentInNewWindow: ((String) -> Unit)? = null
) {
    // The PDF pages drawn by PDFium, the formulas by MicroTeX, the shapes of the sample by their renderer
    val engines = remember {
        PageboardEngines(
            pdfTiles = PdfiumEngine::createTileAdapter,
            formulas = SampleData.latex,
            elements = ElementRenderers(listOf(ShapeRenderer)),
            images = SampleData.images
        )
    }
    val shapes = remember { shapesExtension() + inkLatencyExtension() + wordSuggestionsExtension() }
    // The documents of the sample, in its data directory (see SampleData)
    val documents = SampleData.documents
    PageboardTheme {
        CommandRoot(host = commandHost) {
            DocumentLibrary(
                title = { Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.titleLarge) },
                documents = documents,
                sessions = SampleData.sessions,
                engines = engines,
                thumbnails = rememberDocumentThumbnails(documents, DocumentThumbnails.defaultDirectory(), engines),
                editorExtension = shapes,
                onOpenDocumentInNewWindow = onOpenDocumentInNewWindow,
                clipboard = editorClipboard,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * A standalone document editor window for the Pageboard sample (multi-window desktop mode).
 */
@Composable
fun SampleDocumentWindow(
    documentId: String,
    onClose: () -> Unit,
    commandHost: CommandHost = remember { CommandHost() },
    onTitleChange: (String) -> Unit = {},
    onModifiedChange: (Boolean) -> Unit = {}
) {
    val engines = remember {
        PageboardEngines(
            pdfTiles = PdfiumEngine::createTileAdapter,
            formulas = SampleData.latex,
            elements = ElementRenderers(listOf(ShapeRenderer)),
            images = SampleData.images
        )
    }
    val shapes = remember { shapesExtension() + inkLatencyExtension() + wordSuggestionsExtension() }
    val documents = SampleData.documents
    PageboardTheme {
        val editorViewModel = viewModel(key = "sample-doc-$documentId") {
            DocumentEditorViewModel(documents, SampleData.sessions, engines, clipboard = editorClipboard).apply {
                openDocument(documentId)
            }
        }
        val session = editorViewModel.session
        val title = session?.controller?.document?.observe { title }?.value
        LaunchedEffect(title) {
            title?.let(onTitleChange)
        }
        // Whether the document has changes to undo, for the frame of the window
        val modified = session?.controller?.historyManager?.canUndo == true
        LaunchedEffect(modified) { onModifiedChange(modified) }
        CommandRoot(host = commandHost) {
            if (session != null) {
                EditorSessionScreen(
                    session = session,
                    onClose = onClose,
                    extension = shapes
                )
            }
        }
    }
}
