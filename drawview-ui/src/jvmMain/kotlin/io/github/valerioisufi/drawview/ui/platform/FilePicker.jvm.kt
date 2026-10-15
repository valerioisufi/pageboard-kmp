package io.github.valerioisufi.drawview.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import io.github.valerioisufi.drawview.canvas.controller.MediaManager
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


@Composable
public actual fun rememberFilePicker(type: PickerType, onPicked: (PickedFile) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val currentOnPicked by rememberUpdatedState(onPicked)
    return remember(type) {
        {
            val extensions = when (type) {
                PickerType.IMAGE -> MediaManager.IMAGE_EXTENSIONS
                PickerType.PDF -> setOf("pdf")
            }
            val dialog = FileDialog(null as Frame?, null, FileDialog.LOAD).apply {
                setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in extensions }
                // Windows ignores the filename filter: the pattern restricts the listed files
                file = extensions.joinToString(";") { "*.$it" }
                isMultipleMode = false
            }
            // Modal: returns when the dialog is closed
            dialog.isVisible = true
            val name = dialog.file
            val directory = dialog.directory
            if (name != null && directory != null) {
                val file = File(directory, name)
                scope.launch {
                    val bytes = withContext(Dispatchers.IO) {
                        try {
                            file.readBytes()
                        } catch (_: Exception) {
                            null
                        }
                    }
                    if (bytes != null) currentOnPicked(PickedFile(file.name, bytes))
                }
            }
        }
    }
}

@Composable
internal actual fun HideSystemBarsEffect() {
}
