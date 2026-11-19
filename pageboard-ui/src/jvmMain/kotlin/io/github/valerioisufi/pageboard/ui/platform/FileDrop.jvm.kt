package io.github.valerioisufi.pageboard.ui.platform

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import java.io.File
import java.net.URI

/** The files of the system dragged onto the window (their URIs read as files). */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
public actual fun Modifier.fileDropTarget(onFiles: (List<PickedFile>) -> Boolean): Modifier {
    val current by rememberUpdatedState(onFiles)
    val target = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val files = (event.dragData() as? DragData.FilesList)?.readFiles().orEmpty().mapNotNull { uri ->
                    runCatching { File(URI(uri)) }.getOrNull()?.takeIf { it.isFile }?.let { PickedFile(it.name, it.readBytes()) }
                }
                return files.isNotEmpty() && current(files)
            }
        }
    }
    return dragAndDropTarget(shouldStartDragAndDrop = { it.dragData() is DragData.FilesList }, target = target)
}
