package io.github.valerioisufi.pageboard.ui.editor

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException

/** A text and its HTML, for the clipboard of the desktop. */
private class HtmlSelection(private val plain: String, private val html: String) : Transferable {
    private val flavors = arrayOf(DataFlavor.allHtmlFlavor, DataFlavor.fragmentHtmlFlavor, DataFlavor.selectionHtmlFlavor, DataFlavor.stringFlavor)

    override fun getTransferDataFlavors(): Array<DataFlavor> = flavors.clone()

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavors.any { it.equals(flavor) }

    override fun getTransferData(flavor: DataFlavor): Any = when {
        flavor.equals(DataFlavor.stringFlavor) -> plain
        flavors.any { it.equals(flavor) } -> html
        else -> throw UnsupportedFlavorException(flavor)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun htmlClipEntry(plain: String, html: String): ClipEntry? = ClipEntry(HtmlSelection(plain, html))

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun plainTextOf(entry: ClipEntry): String? {
    val contents = entry.nativeClipEntry as? Transferable ?: return null
    if (!contents.isDataFlavorSupported(DataFlavor.stringFlavor)) return null
    return contents.getTransferData(DataFlavor.stringFlavor) as? String
}
