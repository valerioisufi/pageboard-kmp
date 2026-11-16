package io.github.valerioisufi.drawview.canvas

import androidx.compose.ui.graphics.Canvas
import io.github.valerioisufi.drawview.canvas.controller.DocumentFind
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.canvas.render.tile.TextBlockLayout
import io.github.valerioisufi.drawview.canvas.render.tile.TextFace
import io.github.valerioisufi.drawview.canvas.render.tile.TextTileAdapter
import io.github.valerioisufi.drawview.canvas.render.tile.TextWord
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Finding a text in the document open (see `docs/plans/find-in-document.md`). */
class DocumentFindTest {

    /**
     * Lays a text out as the editor shows it, simplified: the notation `**` taken away, a line of
     * the source a line of 10 mm, each character 2 mm wide.
     */
    private class WordsAdapter : TextTileAdapter {
        override fun drawText(canvas: Canvas, text: DrawText, mmToCanvas: Matrix2D, paperColor: Int) {}
        override fun layout(text: DrawText, paperColor: Int): TextBlockLayout {
            val words = ArrayList<TextWord>()
            for ((line, source) in text.text.replace("**", "").split('\n').withIndex()) {
                var left = 0f
                for (word in Regex("""\S+\s*""").findAll(source)) {
                    words += TextWord(word.value, TextFace.REGULAR, 12f, 0, left, line * 10f + 8f, word.value.length * 2f, line * 10f, line * 10f + 10f)
                    left += word.value.length * 2f
                }
            }
            return TextBlockLayout(words, emptyList(), words.size * 10f)
        }
    }

    private fun TestScope.controller(vararg pages: List<String>): DrawViewController {
        val document = DrawDocument()
        repeat(pages.size - 1) { document.addPage() }
        val controller = DrawViewController(document = document, coroutineScope = backgroundScope)
        controller.viewport.setSize(800f, 1200f)
        controller.tileManager.tileRenderer.textAdapter = WordsAdapter()
        for ((index, texts) in pages.withIndex()) {
            val page = document.pages[index]
            val elements = texts.mapIndexed { k, source -> DrawText().apply { text = source; x = 10f; y = 20f + 40f * k; width = 150f; fontSize = 12f } }
            if (elements.isNotEmpty()) controller.session.submit(AddElements.of(page, elements))
        }
        return controller
    }

    @Test
    fun thePlacesAreFoundInTheOrderOfThePagesWithoutCapitalsOrAccents() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(listOf("Il **teorema** di Rolle", "Città e citta"), listOf("Un altro TEOREMA"))
        val find = controller.find
        find.open()
        find.search("teorema")
        advanceUntilIdle()
        assertEquals(listOf(0, 1), find.matches.map { it.pageIndex })
        assertEquals(0, find.current, "the first one shown")
        find.search("citta")
        advanceUntilIdle()
        assertEquals(2, find.matches.size, "città and citta")
        find.close()
    }

    @Test
    fun aPlaceIsWhereItsWordsAreShownOnThePage() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(listOf("Il **teorema** di Rolle"))
        val find = controller.find
        find.open()
        find.search("teorema di")
        advanceUntilIdle()
        val match = find.matches.single()
        assertEquals(DocumentFind.Source.TEXT, match.source)
        // "Il " is 3 characters (6 mm), "teorema di" 10 (20 mm), on the first line of the box at (10, 20)
        val rect = match.rects.single()
        assertEquals(16f, rect.left, 0.01f)
        assertEquals(36f, rect.right, 0.01f)
        assertEquals(20f, rect.top, 0.01f)
        assertEquals(30f, rect.bottom, 0.01f)
        find.close()
    }

    @Test
    fun aPlaceOverTwoLinesHasARectangleEach() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(listOf("fine della\nriga"))
        val find = controller.find
        find.open()
        find.search("della riga")
        advanceUntilIdle()
        assertEquals(2, find.matches.single().rects.size)
        find.close()
    }

    @Test
    fun nextAndPreviousGoRound() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(listOf("a x a"), listOf("a"))
        val find = controller.find
        find.open()
        find.search("a")
        advanceUntilIdle()
        assertEquals(3, find.matches.size)
        assertEquals(0, find.current)
        find.next()
        find.next()
        assertEquals(2, find.current)
        find.next()
        assertEquals(0, find.current, "after the last, the first")
        find.previous()
        assertEquals(2, find.current, "before the first, the last")
        find.close()
    }

    @Test
    fun aChangeOfTheDocumentIsFoundAgainAndClosingTakesThePlacesAway() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(listOf("uno"))
        val find = controller.find
        find.open()
        find.search("due")
        advanceUntilIdle()
        assertTrue(find.matches.isEmpty())
        assertFalse(find.isSearching)
        val page = controller.document.pages[0]
        val added = DrawText().apply { text = "due"; x = 10f; y = 100f; width = 150f; fontSize = 12f }
        controller.session.submit(AddElements.of(page, listOf(added)))
        // After the document stayed unchanged a while
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, find.matches.size)
        find.close()
        assertTrue(find.matches.isEmpty())
        assertFalse(find.isOpen)
    }

    @Test
    fun emptyOrBlankFindsNothing() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(listOf("a b"))
        controller.find.open()
        controller.find.search("   ")
        advanceUntilIdle()
        assertTrue(controller.find.matches.isEmpty())
        controller.find.close()
    }
}
