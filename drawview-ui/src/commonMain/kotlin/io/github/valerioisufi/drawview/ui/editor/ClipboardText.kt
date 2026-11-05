package io.github.valerioisufi.drawview.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import io.github.valerioisufi.drawview.html.HtmlToMarkdown
import io.github.valerioisufi.drawview.ui.generated.resources.Res
import io.github.valerioisufi.drawview.ui.generated.resources.paste_equation_placeholder
import org.jetbrains.compose.resources.stringResource

/**
 * The HTML another application put in [clipboard], as it is written there on this platform, or
 * `null` when it has none. It reads the clipboard of the platform directly, whichever the [Clipboard]
 * is, and it may throw when the clipboard is busy.
 */
internal expect fun htmlIn(clipboard: Clipboard): String?

/**
 * What the clipboard holds in the notation of the texts, for the canvas and for a text being written
 * (see `docs/html-paste.md`).
 *
 * @param readHtml reads the HTML of the clipboard.
 */
internal class ClipboardText(equationPlaceholder: String, private val readHtml: () -> String?) {
    private val converter = HtmlToMarkdown(equationPlaceholder)

    // The field asks at every change of focus whether it can paste: the conversion is done once
    private var lastHtml: String? = null
    private var lastMarkdown: String? = null

    /**
     * The formatted text of the clipboard in the notation; `null` when the plain text should be taken:
     * there is no HTML, it has no formatting or it cannot be converted.
     */
    fun markdown(): String? {
        val html = try {
            readHtml()
        } catch (_: Exception) {
            // A clipboard busy in another application, or changing while it is read
            null
        } ?: return null
        if (html != lastHtml) {
            lastHtml = html
            lastMarkdown = converter.convert(html)
        }
        return lastMarkdown
    }
}

/** The [ClipboardText] of the clipboard of the composition. */
@Composable
internal fun rememberClipboardText(): ClipboardText {
    val clipboard = LocalClipboard.current
    val placeholder = stringResource(Res.string.paste_equation_placeholder)
    return remember(clipboard, placeholder) { ClipboardText(placeholder) { htmlIn(clipboard) } }
}
