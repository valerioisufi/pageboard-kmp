package io.github.valerioisufi.pageboard.ui.editor

import androidx.compose.ui.platform.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.InputStream

private const val WINDOWS_HEADER_START = "Version:"
private const val FRAGMENT_START = "<!--StartFragment-->"
private const val FRAGMENT_END = "<!--EndFragment-->"

/** The fragment copied before the whole document: the latter has the styles of a page around it. */
internal actual fun htmlIn(clipboard: Clipboard): String? {
    val awt = clipboard.nativeClipboard as? java.awt.datatransfer.Clipboard ?: return null
    val contents = awt.getContents(null) ?: return null
    return htmlFlavorsOf(contents).firstNotNullOfOrNull { contents.getTransferData(it) as? String }?.let(::withoutWindowsHeader)
}

/** The rich text of the clipboard (`text/rtf`, as macOS and Windows give it), read as the bytes it is written in. */
internal actual fun rtfIn(clipboard: Clipboard): String? {
    val awt = clipboard.nativeClipboard as? java.awt.datatransfer.Clipboard ?: return null
    val contents = awt.getContents(null) ?: return null
    val flavor = contents.transferDataFlavors.firstOrNull { it.isMimeTypeEqual("text/rtf") && InputStream::class.java.isAssignableFrom(it.representationClass) } ?: return null
    val bytes = (contents.getTransferData(flavor) as? InputStream)?.use { it.readNBytes(MAX_RTF_BYTES) } ?: return null
    // RTF is written in 7 bits, its other characters escaped
    return String(bytes, Charsets.ISO_8859_1)
}

/** The most bytes of rich text read. */
private const val MAX_RTF_BYTES = 4_000_000

private fun htmlFlavorsOf(contents: Transferable): List<DataFlavor> {
    val known = listOf(DataFlavor.fragmentHtmlFlavor, DataFlavor.allHtmlFlavor).filter(contents::isDataFlavorSupported)
    val others = contents.transferDataFlavors.filter { it.isMimeTypeEqual("text/html") && it.representationClass == String::class.java }
    return known + others
}

/**
 * [html] without what the clipboard of Windows writes around it (the format `CF_HTML`), when it comes
 * as it is: a header of lines (`Version:0.9`, `StartHTML:…`) that would read as text, then a document
 * marking its fragment with two comments. The offsets of the header are not used: they count the bytes
 * of the source, not the characters read.
 */
internal fun withoutWindowsHeader(html: String): String {
    if (!html.startsWith(WINDOWS_HEADER_START)) return html
    val start = html.indexOf(FRAGMENT_START)
    val end = html.lastIndexOf(FRAGMENT_END)
    if (start >= 0 && end > start) return html.substring(start + FRAGMENT_START.length, end)
    val document = html.indexOf('<')
    return if (document >= 0) html.substring(document) else html
}
