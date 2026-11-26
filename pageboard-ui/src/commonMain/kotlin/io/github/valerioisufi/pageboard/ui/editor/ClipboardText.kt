package io.github.valerioisufi.pageboard.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import io.github.valerioisufi.pageboard.html.HtmlToMarkdown
import io.github.valerioisufi.pageboard.html.RtfToMarkdown
import io.github.valerioisufi.pageboard.ui.generated.resources.Res
import io.github.valerioisufi.pageboard.ui.generated.resources.paste_equation_placeholder
import org.jetbrains.compose.resources.stringResource

/**
 * The HTML another application put in [clipboard], as it is written there on this platform, or
 * `null` when it has none. It reads the clipboard of the platform directly, whichever the [Clipboard]
 * is, and it may throw when the clipboard is busy.
 */
internal expect fun htmlIn(clipboard: Clipboard): String?

/**
 * The rich text (RTF) another application put in [clipboard], or `null` when it has none (see
 * `docs/plans/import-and-clipboard.md`): read when the clipboard has no HTML. It may throw when the
 * clipboard is busy.
 */
internal expect fun rtfIn(clipboard: Clipboard): String?

/**
 * What the clipboard holds in the notation of the texts, for the canvas and for a text being written
 * (see `docs/plans/html-paste.md`).
 *
 * @param readHtml reads the HTML of the clipboard.
 * @param readRtf reads its rich text (RTF), when it has no HTML.
 */
internal class ClipboardText(
    equationPlaceholder: String,
    private val readRtf: () -> String? = { null },
    private val readHtml: () -> String?
) {
    private val converter = HtmlToMarkdown(equationPlaceholder)
    private val rtfConverter = RtfToMarkdown()

    // The field asks at every change of focus whether it can paste: the conversion is done once
    private var lastHtml: String? = null
    private var lastMarkdown: String? = null

    /**
     * The formatted text of the clipboard in the notation; `null` when the plain text should be taken:
     * there is no HTML, it has no formatting or it cannot be converted.
     */
    fun markdown(): String? {
        val html = read(readHtml)
        // An application with no HTML: its rich text
        val rtf = if (html == null) read(readRtf) else null
        val content = html ?: rtf ?: return null
        if (content != lastHtml) {
            lastHtml = content
            lastMarkdown = if (html != null) converter.convert(html) else rtfConverter.convert(content)
        }
        return lastMarkdown
    }

    /** What [reader] reads, `null` when the clipboard is busy in another application or changing while it is read. */
    private fun read(reader: () -> String?): String? = try {
        reader()
    } catch (_: Exception) {
        null
    }
}

/** The [ClipboardText] of the clipboard of the composition. */
@Composable
internal fun rememberClipboardText(): ClipboardText {
    val clipboard = LocalClipboard.current
    val placeholder = stringResource(Res.string.paste_equation_placeholder)
    return remember(clipboard, placeholder) { ClipboardText(placeholder, readRtf = { rtfIn(clipboard) }) { htmlIn(clipboard) } }
}
