package io.github.valerioisufi.drawview.ui.editor

import androidx.compose.ui.platform.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable

/** The fragment copied before the whole document: the latter has the styles of a page around it. */
internal actual fun htmlIn(clipboard: Clipboard): String? {
    val awt = clipboard.nativeClipboard as? java.awt.datatransfer.Clipboard ?: return null
    val contents = awt.getContents(null) ?: return null
    return htmlFlavorsOf(contents).firstNotNullOfOrNull { contents.getTransferData(it) as? String }
}

private fun htmlFlavorsOf(contents: Transferable): List<DataFlavor> {
    val known = listOf(DataFlavor.fragmentHtmlFlavor, DataFlavor.allHtmlFlavor).filter(contents::isDataFlavorSupported)
    val others = contents.transferDataFlavors.filter { it.isMimeTypeEqual("text/html") && it.representationClass == String::class.java }
    return known + others
}
