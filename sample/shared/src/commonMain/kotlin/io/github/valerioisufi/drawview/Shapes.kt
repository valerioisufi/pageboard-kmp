package io.github.valerioisufi.drawview

import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.canvas.render.ElementRenderer
import io.github.valerioisufi.drawview.model.ElementType
import io.github.valerioisufi.drawview.ui.extension.EditorExtension
import io.github.valerioisufi.drawview.ui.screen.EditorActionButton
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.serialization.Serializable

/*
 * An element type of the application: how an application adds its own kind of element to the
 * documents (its value, how it is drawn, how it is added in the editor). The editor moves,
 * rotates, resizes, copies and removes the shapes by their box; the documents keep them, the
 * other devices receive them (and draw a placeholder when they do not know the type).
 */

/** A shape of the sample: its [kind] and its color (ARGB). */
@Serializable
data class Shape(val kind: Kind, val color: Int) {
    enum class Kind { STAR, ELLIPSE, RECTANGLE }
}

/** The shapes as a type of element: its identifier is the same on every device and version. */
object ShapeType : ElementType<Shape> {
    override val id = "io.github.valerioisufi.drawview.sample.shape"
    override val serializer = Shape.serializer()
}

/** Draws the shapes in their box (millimeters of the box): filled, with a darker outline. */
object ShapeRenderer : ElementRenderer<Shape> {
    override val type = ShapeType

    override fun draw(canvas: Canvas, value: Shape, widthMm: Float, heightMm: Float) {
        val path = outline(value.kind, widthMm, heightMm)
        canvas.drawPath(path, Paint().apply { color = Color(value.color); isAntiAlias = true })
        canvas.drawPath(path, Paint().apply {
            color = Color(value.color).copy(alpha = 1f).darker()
            style = PaintingStyle.Stroke
            strokeWidth = 0.6f
            isAntiAlias = true
        })
    }

    override fun hits(value: Shape, xMm: Float, yMm: Float, widthMm: Float, heightMm: Float): Boolean = when (value.kind) {
        Shape.Kind.RECTANGLE, Shape.Kind.STAR -> true
        Shape.Kind.ELLIPSE -> {
            val dx = (xMm - widthMm / 2f) / (widthMm / 2f)
            val dy = (yMm - heightMm / 2f) / (heightMm / 2f)
            dx * dx + dy * dy <= 1f
        }
    }

    private fun outline(kind: Shape.Kind, width: Float, height: Float): Path = Path().apply {
        when (kind) {
            Shape.Kind.RECTANGLE -> addRect(Rect(0f, 0f, width, height))
            Shape.Kind.ELLIPSE -> addOval(Rect(0f, 0f, width, height))
            Shape.Kind.STAR -> {
                val center = Offset(width / 2f, height / 2f)
                for (i in 0 until 10) {
                    val radius = if (i % 2 == 0) 1f else 0.42f
                    val angle = -PI / 2 + i * PI / 5
                    val point = Offset(center.x + cos(angle).toFloat() * radius * width / 2f, center.y + sin(angle).toFloat() * radius * height / 2f)
                    if (i == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                }
                close()
            }
        }
    }

    private fun Color.darker() = Color(red * 0.6f, green * 0.6f, blue * 0.6f, alpha)
}

/**
 * The button of the editor adding a shape in the middle of the page shown, each time of the next
 * kind and color.
 */
fun shapesExtension(): EditorExtension = EditorExtension(
    toolbarActions = { context ->
        val next = remember(context) { ShapeSequence() }
        EditorActionButton(DrawViewIcons.Star, "Add a shape", onClick = { addShape(context.controller, next.next()) })
    }
)

private fun addShape(controller: DrawViewController, shape: Shape) {
    val pageIndex = controller.viewport.mostVisiblePageIndex() ?: return
    val page = controller.document.pages.getOrNull(pageIndex) ?: return
    val side = min(page.widthMm, page.heightMm) / 4f
    val box = io.github.valerioisufi.drawview.model.geometry.Rect(
        (page.widthMm - side) / 2f, (page.heightMm - side) / 2f, (page.widthMm + side) / 2f, (page.heightMm + side) / 2f
    )
    controller.customElementManager.add(ShapeType, shape, pageIndex, box)
}

private class ShapeSequence {
    private var count = 0

    fun next(): Shape {
        val kinds = Shape.Kind.entries
        val colors = listOf(0xCCFFC107.toInt(), 0xCC4CAF50.toInt(), 0xCC2196F3.toInt(), 0xCCE91E63.toInt())
        return Shape(kinds[count % kinds.size], colors[count % colors.size]).also { count++ }
    }
}
