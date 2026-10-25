package io.github.valerioisufi.drawview.ui.render

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.font.createFontFamilyResolver
import io.github.valerioisufi.drawview.canvas.render.tile.TextTileAdapter
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The height a text is measured at holds every line it draws, at the scales of the tiles: the
 * tiles choose the texts by their box (see docs/text-heights.md).
 */
class TextHeightTest {

    private val adapter = ComposeTextTileAdapter(createFontFamilyResolver())

    /** A note of the lecture assistant: a title and two items whose next lines hang. */
    private val note = DrawText().apply {
        text = """
            **Why LST is dynamic at the level of the job**
            - **LST has a dynamic priority at the level of the job**: the priority depends on the **slack** (the time left before the deadline less the time of execution left).
            - When a ready job is *not* executed while the time goes by, its slack shrinks and so its priority grows dynamically over time.
        """.trimIndent()
        width = 67f
        height = 10f
        fontSize = 11f
        color = 0xFF1F5AA6.toInt()
    }

    /** The lowest row of pixels [text] draws at [pxPerMm], in millimeters from its top. */
    private fun drawnBottom(text: DrawText, room: Float, pxPerMm: Float): Float {
        val bitmap = ImageBitmap(ceil(text.width * pxPerMm).toInt() + 2, ceil(room * pxPerMm).toInt())
        adapter.drawText(Canvas(bitmap), text, Matrix2D().postScale(pxPerMm, pxPerMm), TextTileAdapter.NO_PAPER)
        val pixels = bitmap.toPixelMap()
        for (y in pixels.height - 1 downTo 0) {
            for (x in 0 until pixels.width) if (pixels[x, y].alpha > 0f) return (y + 1) / pxPerMm
        }
        return 0f
    }

    @Test
    fun theMeasuredHeightHoldsEveryLineDrawnAtEveryScale() {
        val measured = assertNotNull(adapter.measure(note)).height
        val line = assertNotNull(adapter.lineMetrics(note.fontSize)).height
        for (pxPerMm in listOf(0.75f, 1f, 1.5f, 2f, 3f, 4.7f, 6f, 8f, 9.4f, 12f, 18.8f, 24f, 32f, 64f)) {
            val bottom = drawnBottom(note, measured * 1.5f, pxPerMm)
            assertTrue(bottom <= measured + 1f / pxPerMm, "at $pxPerMm px/mm the lines end at $bottom mm, below the box of $measured mm")
            assertTrue(bottom > measured - line, "at $pxPerMm px/mm the last line is drawn ($bottom mm of $measured mm)")
        }
    }
}
