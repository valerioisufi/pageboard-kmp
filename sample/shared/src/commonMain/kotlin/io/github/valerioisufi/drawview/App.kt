package io.github.valerioisufi.drawview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.valerioisufi.drawview.canvas.controller.EditorClipboard
import io.github.valerioisufi.drawview.canvas.observe
import io.github.valerioisufi.drawview.canvas.render.DrawViewEngines
import io.github.valerioisufi.drawview.canvas.render.ElementRenderers
import io.github.valerioisufi.drawview.explorer.DocumentLibrary
import io.github.valerioisufi.drawview.explorer.DocumentThumbnails
import io.github.valerioisufi.drawview.explorer.rememberDocumentThumbnails
import io.github.valerioisufi.drawview.latex.LatexEngine
import io.github.valerioisufi.drawview.pdf.PdfiumEngine
import io.github.valerioisufi.drawview.session.DocumentEditorViewModel
import io.github.valerioisufi.drawview.session.EditorSessionScreen
import io.github.valerioisufi.drawview.ui.command.CommandHost
import io.github.valerioisufi.drawview.ui.command.CommandRoot
import io.github.valerioisufi.drawview.ui.generated.resources.Res
import io.github.valerioisufi.drawview.ui.generated.resources.app_name
import io.github.valerioisufi.drawview.ui.theme.DrawViewTheme
import org.jetbrains.compose.resources.stringResource

/**
 * What is copied and pasted in the editors of the sample: one for the process, shared by the editors
 * of all its documents and windows, so that what is copied in a document is pasted in another one
 * (it outlives the activities of Android, recreated on a rotation).
 */
private val editorClipboard = EditorClipboard()

/**
 * DrawView sample application: the document library and the editor of the open document, with
 * the DrawView theme, and an element type of its own (the shapes, see [ShapeType]).
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
        DrawViewEngines(
            pdfTiles = PdfiumEngine::createTileAdapter,
            formulas = LatexEngine.formulaRenderer,
            elements = ElementRenderers(listOf(ShapeRenderer))
        )
    }
    val shapes = remember { shapesExtension() + inkLatencyExtension() + wordSuggestionsExtension() }
    // The documents of the sample, in its data directory (see SampleData)
    val documents = SampleData.documents
    DrawViewTheme {
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
 * A standalone document editor window for the DrawView sample (multi-window desktop mode).
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
        DrawViewEngines(
            pdfTiles = PdfiumEngine::createTileAdapter,
            formulas = LatexEngine.formulaRenderer,
            elements = ElementRenderers(listOf(ShapeRenderer))
        )
    }
    val shapes = remember { shapesExtension() + inkLatencyExtension() + wordSuggestionsExtension() }
    val documents = SampleData.documents
    DrawViewTheme {
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
