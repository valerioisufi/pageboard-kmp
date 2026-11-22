package io.github.valerioisufi.pageboard.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import io.github.valerioisufi.pageboard.platform.PageboardLog
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
public actual fun rememberFileSaver(title: String, mimeType: String, onTarget: (FileTarget) -> Unit): (suggestedName: String) -> Unit {
    val target by rememberUpdatedState(onTarget)
    return { name -> chooseFileToSave(title, name)?.let { target(FileTarget(it)) } }
}

/** The desktop has no share sheet the application can open: the file is saved, then shown. */
@Composable
public actual fun rememberFileSharer(title: String, mimeType: String, onTarget: (FileTarget) -> Unit): ((suggestedName: String) -> Unit)? = null

public actual fun fileReveal(): FileReveal? = DesktopFileReveal

/** The folder where the last file was saved, where the next save dialog opens (while the application runs). */
@Volatile
private var lastDirectory: String? = null

private fun chooseFileToSave(title: String, suggestedName: String): String? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE).apply {
        file = suggestedName
        lastDirectory?.let { directory = it }
    }
    dialog.isVisible = true
    val name = dialog.file ?: return null
    val directory = dialog.directory ?: return null
    lastDirectory = directory
    return File(directory, name).absolutePath
}

/** The Finder, the Explorer or the file manager of Linux. */
private val DesktopFileReveal = FileReveal(
    show = { path ->
        val os = System.getProperty("os.name").lowercase()
        runCatching {
            when {
                "mac" in os -> ProcessBuilder("open", "-R", path).start()
                "win" in os -> ProcessBuilder("explorer.exe", "/select,", path).start()
                Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE_FILE_DIR) ->
                    Desktop.getDesktop().browseFileDirectory(File(path))
                else -> Desktop.getDesktop().open(File(path).parentFile)
            }
        }.onFailure { PageboardLog.warn("Cannot show the file saved", it) }
    },
    open = { path ->
        runCatching { Desktop.getDesktop().open(File(path)) }.onFailure { PageboardLog.warn("Cannot open the file saved", it) }
    }
)
