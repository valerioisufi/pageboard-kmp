package io.github.valerioisufi.drawview.lecture.platform

import java.awt.FileDialog
import java.awt.Frame
import java.io.File

internal actual val canChooseFiles: Boolean = true

internal actual fun chooseFileToOpen(title: String, extensions: Set<String>): ChosenFile? {
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

internal actual fun chooseFileToSave(title: String, suggestedName: String): String? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE).apply { file = suggestedName }
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory ?: return null, name).absolutePath
}
