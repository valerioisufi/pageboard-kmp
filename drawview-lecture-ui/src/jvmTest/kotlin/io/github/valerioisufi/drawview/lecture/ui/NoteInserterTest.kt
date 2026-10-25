package io.github.valerioisufi.drawview.lecture.ui

import androidx.compose.ui.graphics.Canvas
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.canvas.render.tile.TextTileAdapter
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.geometry.Dimension
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import io.github.valerioisufi.drawview.model.geometry.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * The review inserted as new pages: on the format of the document, which its text is laid out
 * on, whatever the page it follows (docs/new-page-format.md).
 */
class NoteInserterTest {

    /** Lays nothing out: the heights are estimated from the lines. */
    private object NoLayout : TextTileAdapter {
        override fun drawText(canvas: Canvas, text: DrawText, mmToCanvas: Matrix2D, paperColor: Int) {}
    }

    @Test
    fun theReviewAfterAPageOfAnotherFormatTakesTheFormatOfTheDocument() = runTest(UnconfinedTestDispatcher()) {
        val document = DrawDocument(defaultBackground = PageBackground.Ruled())
        val controller = DrawViewController(document = document, coroutineScope = backgroundScope)
        // A slide-sized page of its own background, the one on screen
        controller.pageManager.changeSinglePageTemplate(0, Dimension(254f.mm, 142.875f.mm), PageBackground.Solid())
        // Paragraphs enough for more than one page
        val review = (1..80).joinToString("\n\n") { "Paragraph $it of the review." }

        assertTrue(NoteInserter(NoLayout).insertPages(controller, review, afterPageIndex = 0))

        val inserted = document.pages.drop(1)
        assertTrue(inserted.size > 1)
        for (page in inserted) {
            assertEquals(210f to 297f, page.widthMm to page.heightMm)
            assertEquals(PageBackground.Ruled(), page.background)
            assertFalse(page.hasCustomBackground)
            assertEquals(1, page.texts.size)
        }
    }
}
