package io.github.valerioisufi.drawview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.valerioisufi.drawview.canvas.render.DrawViewEngines
import io.github.valerioisufi.drawview.canvas.render.ElementRenderers
import io.github.valerioisufi.drawview.explorer.DocumentLibrary
import io.github.valerioisufi.drawview.explorer.DocumentThumbnails
import io.github.valerioisufi.drawview.explorer.rememberDocumentThumbnails
import io.github.valerioisufi.drawview.latex.LatexEngine
import io.github.valerioisufi.drawview.pdf.PdfiumEngine
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
 */
@Composable
fun App(commandHost: CommandHost = remember { CommandHost() }) {
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
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
