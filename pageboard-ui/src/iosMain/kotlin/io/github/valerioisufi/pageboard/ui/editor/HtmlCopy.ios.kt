package io.github.valerioisufi.pageboard.ui.editor

import androidx.compose.ui.platform.ClipEntry
import platform.UIKit.UIPasteboard

/** The types of a text on the pasteboard. */
private const val PLAIN_TYPE = "public.utf8-plain-text"
private const val HTML_TYPE = "public.html"

/** Written to the pasteboard here, both types in one item: the clip entries of iOS hold plain text only. */
internal actual fun htmlClipEntry(plain: String, html: String): ClipEntry? {
    UIPasteboard.generalPasteboard.setItems(listOf(mapOf(PLAIN_TYPE to plain, HTML_TYPE to html)))
    return null
}

/** The field of iOS copies through the pasteboard of the system, not here. */
internal actual fun plainTextOf(entry: ClipEntry): String? = null
