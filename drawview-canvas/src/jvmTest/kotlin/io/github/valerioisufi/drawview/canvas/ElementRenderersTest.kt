package io.github.valerioisufi.drawview.canvas

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toPixelMap
import io.github.valerioisufi.drawview.canvas.render.ElementRenderer
import io.github.valerioisufi.drawview.canvas.render.ElementRenderers
import io.github.valerioisufi.drawview.model.DrawCustomElement
import io.github.valerioisufi.drawview.model.ElementType
import io.github.valerioisufi.drawview.model.encode
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable

/** The elements of the types of the application are drawn by their renderer, the others as placeholders. */
class ElementRenderersTest {

    @Serializable
    data class Fill(val color: Int)

    object FillType : ElementType<Fill> {
        override val id = "com.example.fill"
        override val serializer = Fill.serializer()
    }

    /** Fills the left half of its box only (where it can be hit). */
    object FillRenderer : ElementRenderer<Fill> {
        override val type = FillType

        override fun draw(canvas: Canvas, value: Fill, widthMm: Float, heightMm: Float) {
            canvas.drawRect(Rect(0f, 0f, widthMm / 2f, heightMm), Paint().apply { color = Color(value.color) })
        }

        override fun hits(value: Fill, xMm: Float, yMm: Float, widthMm: Float, heightMm: Float) = xMm < widthMm / 2f
    }

    private val renderers = ElementRenderers(listOf(FillRenderer))

    private fun element(type: String, payload: ByteArray) = DrawCustomElement(type).apply {
        this.payload = payload
        x = 10f
        y = 10f
        width = 20f
        height = 20f
    }

    /** [element] drawn on a 40 mm square, a pixel per millimeter. */
    private fun draw(element: DrawCustomElement): ImageBitmap =
        ImageBitmap(40, 40).also { renderers.draw(Canvas(it), element, Matrix2D()) }

    @Test
    fun anElementIsDrawnInItsBoxByItsRenderer() {
        val pixels = draw(element(FillType.id, FillType.encode(Fill(RED)))).toPixelMap()
        assertEquals(Color(RED), pixels[15, 20], "the left half of the box")
        assertEquals(Color.Transparent, pixels[25, 20], "the right half of the box")
        assertEquals(Color.Transparent, pixels[5, 5], "outside of the box")
    }

    @Test
    fun anElementOfATypeNotKnownIsAPlaceholder() {
        val pixels = draw(element("org.other.type", byteArrayOf(1, 2, 3))).toPixelMap()
        // Its diagonals cross in the middle of its box
        assertNotEquals(Color.Transparent, pixels[20, 20])
        assertEquals(Color.Transparent, pixels[5, 5])
    }

    @Test
    fun theHitsOfAnElementAreTheOnesOfItsRenderer() {
        val element = element(FillType.id, FillType.encode(Fill(RED)))
        assertTrue(renderers.hits(element, 15f, 20f))
        assertFalse(renderers.hits(element, 25f, 20f))
        assertFalse(renderers.hits(element, 5f, 5f))
        // A type not known: its whole box
        assertTrue(renderers.hits(element("org.other.type", byteArrayOf()), 25f, 20f))
    }

    @Test
    fun theValueIsReadAgainOnlyWhenItChanges() {
        val element = element(FillType.id, FillType.encode(Fill(RED)))
        val value = renderers.valueOf(element)
        assertSame(value, renderers.valueOf(element))
        element.payload = FillType.encode(Fill(BLUE))
        assertEquals(Fill(BLUE), renderers.valueOf(element))
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
    }
}
