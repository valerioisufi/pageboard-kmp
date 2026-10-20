package io.github.valerioisufi.drawview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.valerioisufi.drawview.canvas.render.DrawViewEngines
import io.github.valerioisufi.drawview.canvas.render.ElementRenderers
import io.github.valerioisufi.drawview.explorer.DocumentLibrary
import io.github.valerioisufi.drawview.explorer.DocumentThumbnails
import io.github.valerioisufi.drawview.explorer.rememberDocumentThumbnails
import io.github.valerioisufi.drawview.latex.LatexEngine
import io.github.valerioisufi.drawview.pdf.PdfiumEngine
import io.github.valerioisufi.drawview.session.DocumentEditorViewModel
import io.github.valerioisufi.drawview.session.EditorSessionScreen
import io.github.valerioisufi.drawview.store.room.DrawViewStore
import io.github.valerioisufi.drawview.ui.command.CommandHost
import io.github.valerioisufi.drawview.ui.command.CommandRoot
import io.github.valerioisufi.drawview.ui.generated.resources.Res
import io.github.valerioisufi.drawview.ui.generated.resources.app_name
import io.github.valerioisufi.drawview.ui.theme.DrawViewTheme
import org.jetbrains.compose.resources.stringResource

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
    val shapes = remember { shapesExtension() }
    // The documents of the sample, in its data directory (see DrawViewStore)
    val documents = DrawViewStore.documents
    DrawViewTheme {
        CommandRoot(host = commandHost) {
            DocumentLibrary(
                title = { Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.titleLarge) },
                documents = documents,
                engines = engines,
                thumbnails = rememberDocumentThumbnails(documents, DocumentThumbnails.defaultDirectory(), engines),
                editorExtension = shapes,
                onOpenDocumentInNewWindow = onOpenDocumentInNewWindow,
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
    onTitleChange: (String) -> Unit = {}
) {
    val engines = remember {
        DrawViewEngines(
            pdfTiles = PdfiumEngine::createTileAdapter,
            formulas = LatexEngine.formulaRenderer,
            elements = ElementRenderers(listOf(ShapeRenderer))
        )
    }
    val shapes = remember { shapesExtension() }
    val documents = DrawViewStore.documents
    DrawViewTheme {
        val editorViewModel = viewModel(key = "sample-doc-$documentId") {
            DocumentEditorViewModel(documents, engines).apply {
                openDocument(documentId)
            }
        }
        val session = editorViewModel.session
        LaunchedEffect(session?.controller?.document?.name) {
            session?.controller?.document?.name?.let(onTitleChange)
        }
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
