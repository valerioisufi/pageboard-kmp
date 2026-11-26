package io.github.valerioisufi.pageboard.ui.editor

import androidx.compose.ui.platform.ClipEntry
import io.github.valerioisufi.pageboard.html.MarkdownToHtml

/**
 * An entry of the clipboard holding [plain], the notation of a text copied, and [html], the same
 * written as HTML for the other applications (see `docs/plans/import-and-clipboard.md`); `null` where
 * the platform takes it another way (iOS: written to the pasteboard here).
 */
internal expect fun htmlClipEntry(plain: String, html: String): ClipEntry?

/** The plain text of [entry], `null` when it holds none this platform reads. */
internal expect fun plainTextOf(entry: ClipEntry): String?

/** [entry], a text copied, with its HTML for the other applications; [entry] as it is when it holds no text read. */
internal fun withHtml(entry: ClipEntry): ClipEntry {
    val plain = plainTextOf(entry)?.takeIf { it.isNotBlank() } ?: return entry
    return htmlClipEntry(plain, MarkdownToHtml.convert(plain)) ?: entry
}
