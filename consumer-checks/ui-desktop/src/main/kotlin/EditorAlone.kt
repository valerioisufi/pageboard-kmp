import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.github.valerioisufi.pageboard.canvas.controller.DrawViewController
import io.github.valerioisufi.pageboard.model.DrawDocument
import io.github.valerioisufi.pageboard.ui.extension.EditorContext
import io.github.valerioisufi.pageboard.ui.screen.EditorScreen
import io.github.valerioisufi.pageboard.ui.theme.DrawViewTheme

/** The editor of a document in memory, without PDF pages nor formulas (drawn as their source). */
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Notes") {
        val scope = rememberCoroutineScope()
        val context = remember { EditorContext(DrawViewController(DrawDocument(), scope)) }
        DrawViewTheme {
            EditorScreen(context, onClose = ::exitApplication)
        }
    }
}
