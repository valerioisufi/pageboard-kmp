package io.github.valerioisufi.pageboard.canvas.render

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.PathEffect
import io.github.valerioisufi.pageboard.canvas.geometry.toComposeMatrix
import io.github.valerioisufi.pageboard.model.DrawCustomElement
import io.github.valerioisufi.pageboard.model.ElementType
import io.github.valerioisufi.pageboard.model.decode
import io.github.valerioisufi.pageboard.model.geometry.Matrix2D
import io.github.valerioisufi.pageboard.model.geometry.Point
import io.github.valerioisufi.pageboard.model.geometry.VectorDrawing
import io.github.valerioisufi.pageboard.platform.SyncLock
import io.github.valerioisufi.pageboard.platform.syncLock

/**
 * Draws the elements of a type added by an application ([ElementType]) in their box: the canvas
 * is in millimeters of the box, (0, 0) its top-left corner before its rotation, (`widthMm`,
 * `heightMm`) its bottom-right one. The editor places, rotates, resizes and selects the elements
 * through their box.
 *
 * Implementations must be thread-safe: the tiles are rasterized on background workers.
 */
public interface ElementRenderer<T : Any> {
    public val type: ElementType<T>

    /** Draws [value] in a box of `widthMm` x `heightMm` millimeters. */
    public fun draw(canvas: Canvas, value: T, widthMm: Float, heightMm: Float)

    /** Whether the point ([xMm], [yMm]) of the box hits the element (by default, its whole box). */
    public fun hits(value: T, xMm: Float, yMm: Float, widthMm: Float, heightMm: Float): Boolean = true

    /**
     * [value] as vectors in a box of `widthMm` x `heightMm` millimeters, written as such in the
     * PDF; `null` (by default) to have it drawn by [draw] as an image.
     */
    public fun vectors(value: T, widthMm: Float, heightMm: Float): VectorDrawing? = null

    /**
     * Whether the eraser, a capsule of [radiusMm] around the segment [from] - [to] of the box,
     * erases [value]; by default never (the eraser erases what is drawn by hand).
     */
    public fun erasedBy(value: T, from: Point, to: Point, radiusMm: Float, widthMm: Float, heightMm: Float): Boolean = false
}

/**
 * The element types an application draws (see [ElementRenderer]), with the ones of the libraries
 * (the shapes, [ShapeElementRenderer]): the elements of the other types (another application, a
 * newer version) are drawn as a placeholder in their box. Given to the editors, the thumbnails and
 * the export by [DrawViewEngines.elements].
 */
public class ElementRenderers(renderers: Collection<ElementRenderer<*>>) {
    private val byType: Map<String, ElementRenderer<*>> = (BUILT_IN + renderers).associateBy { it.type.id }

    private val lock = SyncLock()

    // The value read last of every element, with the payload it was read from: an element changes
    // its payload as a whole (most recently used last)
    private val values = LinkedHashMap<String, Pair<ByteArray, Any?>>()

    /** The renderer of the type [type], if the application knows it. */
    public operator fun get(type: String): ElementRenderer<*>? = byType[type]

    /**
     * The value of [element] as its type reads it, or `null` when its type is not known here or
     * its value cannot be read (another format).
     */
    internal fun valueOf(element: DrawCustomElement): Any? {
        val renderer = byType[element.type] ?: return null
        val payload = element.payload
        syncLock(lock) {
            values.remove(element.id)?.let { cached ->
                values[element.id] = cached
                if (cached.first === payload) return cached.second
            }
        }
        val value = renderer.type.decode(element)
        syncLock(lock) {
            values[element.id] = payload to value
            while (values.size > MAX_VALUES) values.remove(values.keys.first())
        }
        return value
    }

    /** Draws [element] on [canvas], its page mapped to the canvas by [mmToCanvas]. */
    public fun draw(canvas: Canvas, element: DrawCustomElement, mmToCanvas: Matrix2D) {
        if (element.width <= 0f || element.height <= 0f) return
        val boxToCanvas = Matrix2D()
            .postRotate(element.rotation, element.width / 2f, element.height / 2f)
            .postTranslate(element.x, element.y)
            .postConcat(mmToCanvas)
        canvas.save()
        canvas.concat(boxToCanvas.toComposeMatrix())
        try {
            val value = valueOf(element)
            val renderer = byType[element.type]
            if (value != null && renderer != null) renderer.drawValue(canvas, value, element.width, element.height)
            else drawPlaceholder(canvas, element.width, element.height)
        } catch (_: Throwable) {
            // An element that cannot be drawn must not break the rendering of the page
            drawPlaceholder(canvas, element.width, element.height)
        } finally {
            canvas.restore()
        }
    }

    /** Whether the point ([xMm], [yMm]) of the page hits [element] (its box when its type is not known). */
    public fun hits(element: DrawCustomElement, xMm: Float, yMm: Float): Boolean {
        if (!element.containsPoint(xMm, yMm)) return false
        val renderer = byType[element.type] ?: return true
        val value = valueOf(element) ?: return true
        val local = element.toLocal(xMm, yMm)
        return renderer.hitsValue(value, local.x, local.y, element.width, element.height)
    }

    /** [element] as vectors in its box (see [ElementRenderer.vectors]), or `null` when it is drawn as an image. */
    public fun vectorsOf(element: DrawCustomElement): VectorDrawing? {
        if (element.width <= 0f || element.height <= 0f) return null
        val renderer = byType[element.type] ?: return null
        val value = valueOf(element) ?: return null
        return renderer.vectorsValue(value, element.width, element.height)
    }

    /**
     * Whether the eraser, a capsule of [radiusMm] around the segment [from] - [to] of the page,
     * erases [element] (see [ElementRenderer.erasedBy]); never an element of a type not known.
     */
    public fun erasedBy(element: DrawCustomElement, from: Point, to: Point, radiusMm: Float): Boolean {
        val renderer = byType[element.type] ?: return false
        val value = valueOf(element) ?: return false
        return renderer.erasedByValue(value, element.toLocal(from.x, from.y), element.toLocal(to.x, to.y), radiusMm, element.width, element.height)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> ElementRenderer<T>.vectorsValue(value: Any, widthMm: Float, heightMm: Float) =
        vectors(value as T, widthMm, heightMm)

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> ElementRenderer<T>.erasedByValue(value: Any, from: Point, to: Point, radiusMm: Float, widthMm: Float, heightMm: Float) =
        erasedBy(value as T, from, to, radiusMm, widthMm, heightMm)

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> ElementRenderer<T>.drawValue(canvas: Canvas, value: Any, widthMm: Float, heightMm: Float) =
        draw(canvas, value as T, widthMm, heightMm)

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> ElementRenderer<T>.hitsValue(value: Any, xMm: Float, yMm: Float, widthMm: Float, heightMm: Float) =
        hits(value as T, xMm, yMm, widthMm, heightMm)

    /** The box of an element that cannot be drawn: tinted, dashed, crossed. */
    private fun drawPlaceholder(canvas: Canvas, widthMm: Float, heightMm: Float) {
        val box = Rect(0f, 0f, widthMm, heightMm)
        canvas.drawRect(box, Paint().apply { color = PLACEHOLDER_FILL })
        val line = Paint().apply {
            color = PLACEHOLDER_LINE
            style = PaintingStyle.Stroke
            strokeWidth = PLACEHOLDER_LINE_MM
            isAntiAlias = true
        }
        canvas.drawLine(box.topLeft, box.bottomRight, line)
        canvas.drawLine(box.bottomLeft, box.topRight, line)
        line.pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.5f, 1f))
        canvas.drawRect(box, line)
    }

    public companion object {
        /** The types of the libraries, drawn by every editor. */
        private val BUILT_IN: List<ElementRenderer<*>> = listOf(ShapeElementRenderer)

        /** No type of an application (the ones of the libraries only): their elements are drawn as placeholders. */
        public val None: ElementRenderers = ElementRenderers(emptyList())

        private const val MAX_VALUES = 256
        private const val PLACEHOLDER_LINE_MM = 0.3f
        private val PLACEHOLDER_FILL = Color(0x14000000)
        private val PLACEHOLDER_LINE = Color(0x66000000)
    }
}
