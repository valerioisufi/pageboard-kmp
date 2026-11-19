package io.github.valerioisufi.drawview.lecture.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import io.github.valerioisufi.drawview.lecture.platform.ChosenFile
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
internal actual fun rememberFileOpener(title: String, extensions: Set<String>, onChosen: (ChosenFile) -> Unit): () -> Unit {
    val chosen by rememberUpdatedState(onChosen)
    return { chooseFileToOpen(title, extensions)?.let(chosen) }
}

private fun chooseFileToOpen(title: String, extensions: Set<String>): ChosenFile? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD).apply {
        setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in extensions }
        // Windows ignores the filename filter: the pattern restricts the listed files
        file = extensions.joinToString(";") { "*.$it" }
        isMultipleMode = false
    }
    dialog.isVisible = true
    val name = dialog.file ?: return null
    val file = File(dialog.directory ?: return null, name)
    return ChosenFile(file.absolutePath, file.name, file.lastModified().takeIf { it > 0 })
}
