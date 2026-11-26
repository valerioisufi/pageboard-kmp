package io.github.valerioisufi.pageboard.ui.editor

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** A text copied goes to the other applications as its notation and as HTML. */
@OptIn(ExperimentalComposeUiApi::class)
class HtmlCopyTest {

    @Test
    fun aTextCopiedCarriesItsHtml() {
        val entry = withHtml(ClipEntry(StringSelection("A **bold** word")))
        val contents = entry.nativeClipEntry as Transferable
        assertEquals("A **bold** word", contents.getTransferData(DataFlavor.stringFlavor), "its notation as plain text")
        assertEquals("<p>A <strong>bold</strong> word</p>", contents.getTransferData(DataFlavor.allHtmlFlavor))
        assertEquals("<p>A <strong>bold</strong> word</p>", contents.getTransferData(DataFlavor.fragmentHtmlFlavor))
    }

    @Test
    fun anEntryWithNoTextStaysAsItIs() {
        val empty = ClipEntry(StringSelection(" "))
        assertSame(empty, withHtml(empty))
    }
}
