package io.github.valerioisufi.drawview.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
actual fun rememberFileSaver(title: String, mimeType: String, onTarget: (FileTarget) -> Unit): (suggestedName: String) -> Unit {
    val target by rememberUpdatedState(onTarget)
    return { name -> chooseFileToSave(title, name)?.let { target(FileTarget(it)) } }
}

private fun chooseFileToSave(title: String, suggestedName: String): String? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE).apply { file = suggestedName }
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory ?: return null, name).absolutePath
}
