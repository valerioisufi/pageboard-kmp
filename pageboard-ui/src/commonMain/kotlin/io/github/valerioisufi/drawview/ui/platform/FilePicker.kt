package io.github.valerioisufi.drawview.ui.platform

import androidx.compose.runtime.Composable

/**
 * A file picked by the user.
 */
public class PickedFile(public val name: String, public val bytes: ByteArray) {
    /** Lowercase extension of the file name ("" when missing). */
    public val extension: String get() = name.substringAfterLast('.', "").lowercase()

    /** Its content as text (UTF-8, without the mark some editors put at its start). */
    public fun text(): String = bytes.decodeToString().removePrefix("\uFEFF")

    public companion object {
        /** The extensions of the text files that are imported as text (see `docs/plans/text-import.md`). */
        public val TEXT_EXTENSIONS: Set<String> = setOf("md", "markdown", "txt", "text")
    }
}

/** The kinds of files the file pickers choose: [ARCHIVE] is an archive of the application (`.drawview`). */
public enum class PickerType { IMAGE, PDF, TEXT, ARCHIVE }

/**
 * Remembers a launcher of the file picker of the platform (`GetContent` on Android, the file
 * dialog on the desktop, the photo / document pickers on iOS).
 * [onPicked] receives the selected file and is not called when the user cancels.
 */
@Composable
public expect fun rememberFilePicker(type: PickerType, onPicked: (PickedFile) -> Unit): () -> Unit
