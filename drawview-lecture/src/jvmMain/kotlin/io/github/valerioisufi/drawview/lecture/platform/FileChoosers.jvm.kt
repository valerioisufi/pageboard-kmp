package io.github.valerioisufi.drawview.lecture.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
internal actual fun rememberFileOpener(title: String, extensions: Set<String>, onChosen: (ChosenFile) -> Unit): () -> Unit {
    val chosen by rememberUpdatedState(onChosen)
    return { chooseFileToOpen(title, extensions)?.let(chosen) }
}

@Composable
internal actual fun rememberFileSaver(title: String, mimeType: String, onTarget: (FileTarget) -> Unit): (suggestedName: String) -> Unit {
    val target by rememberUpdatedState(onTarget)
    return { name -> chooseFileToSave(title, name)?.let { target(FileTarget(it)) } }
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

private fun chooseFileToSave(title: String, suggestedName: String): String? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE).apply { file = suggestedName }
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory ?: return null, name).absolutePath
}
