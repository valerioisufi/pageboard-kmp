package io.github.valerioisufi.pageboard.ui.platform

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
        /**
         * The extensions of the files that are imported as text (see `docs/plans/text-import.md`): the
         * texts, the pages of HTML and the Word documents (see `docs/plans/import-and-clipboard.md`).
         */
        public val TEXT_EXTENSIONS: Set<String> = setOf("md", "markdown", "txt", "text", "html", "htm", "xhtml", "docx")

        /** The media types of the files imported as text, for the pickers that choose by type. */
        public val TEXT_MIME_TYPES: List<String> = listOf(
            "text/*", "application/xhtml+xml", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/pdf"
        )

        /** The extensions the picker of a text chooses: the texts, and a PDF whose text is taken. */
        public val TEXT_PICKED_EXTENSIONS: Set<String> = TEXT_EXTENSIONS + "pdf"
    }
}

/** The kinds of files the file pickers choose: [ARCHIVE] is an archive of the application (`.pageboard`). */
public enum class PickerType { IMAGE, PDF, TEXT, ARCHIVE }

/**
 * Remembers a launcher of the file picker of the platform (`GetContent` on Android, the file
 * dialog on the desktop, the photo / document pickers on iOS).
 * [onPicked] receives the selected file and is not called when the user cancels.
 */
@Composable
public expect fun rememberFilePicker(type: PickerType, onPicked: (PickedFile) -> Unit): () -> Unit
