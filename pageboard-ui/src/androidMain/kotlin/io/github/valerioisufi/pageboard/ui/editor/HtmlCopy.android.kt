package io.github.valerioisufi.pageboard.ui.editor

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry

internal actual fun htmlClipEntry(plain: String, html: String): ClipEntry? = ClipEntry(ClipData.newHtmlText(null, plain, html))

internal actual fun plainTextOf(entry: ClipEntry): String? {
    val clip = entry.clipData
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).text?.toString()
}
