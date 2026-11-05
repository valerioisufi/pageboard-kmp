package io.github.valerioisufi.drawview.ui.editor

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The HTML of the clipboard read and converted once. */
@OptIn(ExperimentalComposeUiApi::class)
class ClipboardTextTest {

    private class AwtClipboard(contents: Transferable?) : Clipboard {
        override suspend fun getClipEntry(): ClipEntry? = null
        override suspend fun setClipEntry(clipEntry: ClipEntry?) = Unit
        override val nativeClipboard = java.awt.datatransfer.Clipboard("test").also { if (contents != null) it.setContents(contents, null) }
    }

    /** A copy with some [flavors], as another application makes it. */
    private class Copied(private val flavors: Map<DataFlavor, String>) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = flavors.keys.toTypedArray()
        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor in flavors
        override fun getTransferData(flavor: DataFlavor): Any = flavors[flavor] ?: throw UnsupportedFlavorException(flavor)
    }

    @Test
    fun theFragmentOfACopyIsReadBeforeTheWholeDocument() {
        val copied = Copied(
            mapOf(
                DataFlavor.stringFlavor to "plain",
                DataFlavor.allHtmlFlavor to "<html>all</html>",
                DataFlavor.fragmentHtmlFlavor to "<b>fragment</b>"
            )
        )
        assertEquals("<b>fragment</b>", htmlIn(AwtClipboard(copied)))
    }

    @Test
    fun theHeaderOfTheClipboardOfWindowsIsNotText() {
        val header = "Version:0.9\r\nStartHTML:0000000163\r\nEndHTML:0000011225\r\nStartFragment:0000000199\r\nEndFragment:0000011189\r\nSourceURL:https://example.com/\r\n"
        val copied = header + "<html><body><!--StartFragment--><b>a</b><!--EndFragment--></body></html>"
        assertEquals("<b>a</b>", htmlIn(AwtClipboard(Copied(mapOf(DataFlavor.fragmentHtmlFlavor to copied)))))
        assertEquals("<html><b>a</b></html>", withoutWindowsHeader(header + "<html><b>a</b></html>"))
        assertEquals("<b>a</b>", withoutWindowsHeader("<b>a</b>"))
    }

    @Test
    fun aCopyOfPlainTextOrNothingHasNoHtml() {
        assertNull(htmlIn(AwtClipboard(StringSelection("plain"))))
        assertNull(htmlIn(AwtClipboard(null)))
    }

    @Test
    fun theHtmlBecomesTheNotationConvertedOnce() {
        val reads = mutableListOf<String?>("<b>a</b>", "<b>a</b>", "<p>plain</p>", null)
        val text = ClipboardText("[eq]") { reads.removeFirst() }
        assertEquals("**a**", text.markdown())
        assertEquals("**a**", text.markdown())
        assertNull(text.markdown())
        assertNull(text.markdown())
    }

    @Test
    fun aBusyClipboardLeavesThePlainText() {
        assertNull(ClipboardText("[eq]") { throw IllegalStateException("busy") }.markdown())
    }
}
