package io.github.valerioisufi.drawview.ui.editor

import androidx.compose.ui.platform.Clipboard
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSPropertyListImmutable
import platform.Foundation.NSPropertyListSerialization
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create

/** The type of HTML on the pasteboard. */
private const val HTML_TYPE = "public.html"

/** The types of a web archive: what Safari copies, HTML in a property list. */
private val WEB_ARCHIVE_TYPES = listOf("Apple Web Archive pasteboard type", "com.apple.webarchive")

internal actual fun htmlIn(clipboard: Clipboard): String? {
    val pasteboard = clipboard.nativeClipboard
    pasteboard.dataForPasteboardType(HTML_TYPE)?.let { return textOf(it) }
    return WEB_ARCHIVE_TYPES.firstNotNullOfOrNull { type -> pasteboard.dataForPasteboardType(type)?.let(::mainResourceOf) }
}

@Suppress("CAST_NEVER_SUCCEEDS")
private fun textOf(data: NSData): String? = NSString.create(data, NSUTF8StringEncoding) as String?

/** The HTML of the main resource of a web archive in [data]. */
@OptIn(ExperimentalForeignApi::class)
private fun mainResourceOf(data: NSData): String? {
    val archive = NSPropertyListSerialization.propertyListWithData(data, NSPropertyListImmutable, null, null) as? Map<*, *> ?: return null
    val resource = archive["WebMainResource"] as? Map<*, *> ?: return null
    return (resource["WebResourceData"] as? NSData)?.let(::textOf)
}
