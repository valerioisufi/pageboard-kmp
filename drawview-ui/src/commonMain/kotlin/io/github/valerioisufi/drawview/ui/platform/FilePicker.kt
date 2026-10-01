package io.github.valerioisufi.drawview.ui.platform

import androidx.compose.runtime.Composable

/**
 * A file picked by the user.
 */
class PickedFile(val name: String, val bytes: ByteArray) {
    /** Lowercase extension of the file name ("" when missing). */
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
}

enum class PickerType { IMAGE, PDF }

/**
 * Remembers a launcher of the file picker of the platform (`GetContent` on Android, the file
 * dialog on the desktop, the photo / document pickers on iOS, a file input in the browser).
 * [onPicked] receives the selected file and is not called when the user cancels.
 */
@Composable
expect fun rememberFilePicker(type: PickerType, onPicked: (PickedFile) -> Unit): () -> Unit

/**
 * Hides the system bars while the editor is shown (immersive mode of the original app on
 * Android; no-op on the other platforms).
 */
@Composable
expect fun HideSystemBarsEffect()
