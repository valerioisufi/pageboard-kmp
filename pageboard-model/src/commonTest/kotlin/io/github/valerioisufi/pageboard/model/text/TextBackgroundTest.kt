package io.github.valerioisufi.pageboard.model.text

import io.github.valerioisufi.pageboard.model.DrawText
import io.github.valerioisufi.pageboard.model.change.TextData
import io.github.valerioisufi.pageboard.model.change.toData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The fill of a text box: kept with its content, around its box, in its bounds. */
class TextBackgroundTest {

    private fun text(background: Int?) = DrawText("t").apply {
        x = 10f; y = 20f; width = 40f; height = 10f; fontSize = 72f
        this.background = background
    }

    @Test
    fun theFillGoesAroundTheBoxByAMarginOfTheSize() {
        // 72 pt is an inch: the margin is 0.35 of it
        val outset = 0.35f * 25.4f
        assertEquals(outset, TextBackground.outsetMm(72f), 1e-4f)
        val bounds = text(0xFFFFF1B8.toInt()).getRotatedBounds()
        assertEquals(10f - outset, bounds.left, 1e-4f)
        assertEquals(20f - outset, bounds.top, 1e-4f)
        assertEquals(50f + outset, bounds.right, 1e-4f)
        assertEquals(30f + outset, bounds.bottom, 1e-4f)
        assertEquals(text(null).getRotatedBounds().left, 10f, "no fill, the box")
    }

    @Test
    fun aTapOnTheFillIsOnTheText() {
        assertTrue(text(0xFFFFF1B8.toInt()).containsPoint(9f, 25f))
        assertFalse(text(null).containsPoint(9f, 25f))
    }

    @Test
    fun theFillIsPartOfTheContent() {
        val filled = text(0xFFD7E8FA.toInt())
        val data = filled.toData()
        assertEquals(0xFFD7E8FA.toInt(), data.background)
        val copy = DrawText("t")
        data.applyTo(copy)
        assertEquals(0xFFD7E8FA.toInt(), copy.background)
        assertEquals(null, TextData("u", "a", x = 0f, y = 0f, width = 1f, color = 0, fontSize = 1f).background, "none by default")
    }
}
