package io.github.valerioisufi.drawview.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.valerioisufi.drawview.ui.adaptive.AdaptiveRoot
import io.github.valerioisufi.drawview.ui.extension.EditorContext
import io.github.valerioisufi.drawview.ui.extension.EditorExtension
import io.github.valerioisufi.drawview.ui.screen.EditorScreen
import kotlinx.coroutines.flow.combine

/**
 * The editor of an open document: a progress bar while the document is being loaded, then the
 * [EditorScreen], with the folder of the document under its title. [onClose] is called by the
 * back button of the editor and by the system back.
 *
 * @param extension content contributed to the editor by the application (top bar actions, panels,
 *   commands, overlays).
 */
@Composable
fun EditorSessionScreen(
    session: EditorSession,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    extension: EditorExtension? = null
) {
    val controller = session.controller
    if (controller == null) {
        // The document is being loaded
        AdaptiveRoot(modifier = modifier) {
            Box(
                modifier = Modifier.background(MaterialTheme.colorScheme.surface)
            ) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
                    color = MaterialTheme.colorScheme.secondary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
        return
    }
    val context = remember(controller) { EditorContext(controller) }
    // The folder of the document, under its title
    val location by remember(session) {
        combine(session.store.documents, session.store.folders) { documents, folders ->
            val folderId = documents.firstOrNull { it.id == session.documentId }?.folderId
            folders.firstOrNull { it.id == folderId }?.name
        }
    }.collectAsState(null)
    EditorScreen(context, onClose, modifier, location, extension)
}
