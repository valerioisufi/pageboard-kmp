package io.github.valerioisufi.drawview.export

import io.github.valerioisufi.drawview.canvas.render.tile.FormulaDrawing
import io.github.valerioisufi.drawview.canvas.render.tile.FormulaFont
import io.github.valerioisufi.drawview.canvas.render.tile.FormulaOp
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import io.github.valerioisufi.drawview.pdf.PdfPath
import io.github.valerioisufi.drawview.pdf.TrueTypeFont
import kotlin.math.PI
import kotlin.math.max

/** A shape of a formula: [path] filled with [argb], or stroked [strokeWidth] wide. */
internal class FormulaShape(val path: PdfPath, val argb: Int, val strokeWidth: Float = 0f)

/**
 * The shapes of a formula as MicroTeX draws it (see [FormulaDrawing]): its characters as the outlines
 * of their glyphs, its rules and lines as paths, mapped by `toPage` from the pixels of the drawing.
 * It follows the graphics of the platforms (`io.nano.tex.Graphics2D`): the translations and the
 * scales applied to the coordinates, the rotations to what follows them, the styles a font has not
 * synthesized (bold outset, italic slanted).
 *
 * @param fonts the TrueType font of a [FormulaFont] (a file of the TeX resources, or the text of the
 *   formulas), or `null` when it cannot be read: its characters are left out.
 */
internal class FormulaPaths(private val fonts: (FormulaFont) -> TrueTypeFont?) {

    fun shapes(drawing: FormulaDrawing, toPage: Matrix2D): List<FormulaShape> = Replay(toPage).apply { drawing.ops.forEach(::play) }.shapes

    private inner class Replay(private val toPage: Matrix2D) {
        val shapes = mutableListOf<FormulaShape>()

        // The transformation of the coordinates (scales and translation)
        private var sx = 1f
        private var sy = 1f
        private var tx = 0f
        private var ty = 0f

        // The rotation of the canvas: accumulated, around the last point
        private var degrees = 0f
        private var pivotX = 0f
        private var pivotY = 0f
        private val canvas = Matrix2D()

        private var font: FormulaFont? = null
        private var color = 0xFF000000.toInt()
        private var strokeWidth = 1f
        private val pageScale = toPage.scaleFactor

        private fun x(x: Float) = x * sx + tx
        private fun y(y: Float) = y * sy + ty

        fun play(op: FormulaOp) {
            when (op) {
                is FormulaOp.SetFont -> font = op.font
                is FormulaOp.SetColor -> color = op.argb
                is FormulaOp.SetStrokeWidth -> strokeWidth = op.width
                is FormulaOp.SetStroke -> strokeWidth = op.width
                is FormulaOp.Translate -> {
                    tx += sx * op.dx
                    ty += sy * op.dy
                }
                is FormulaOp.Scale -> {
                    sx *= op.sx
                    sy *= op.sy
                }
                is FormulaOp.Rotate -> {
                    val deg = (op.angle / PI * 180).toFloat()
                    degrees += deg
                    pivotX = x(op.px)
                    pivotY = y(op.py)
                    canvas.preConcat(Matrix2D().postRotate(deg, pivotX, pivotY))
                }
                FormulaOp.Reset -> {
                    if (degrees != 0f) canvas.preConcat(Matrix2D().postRotate(-degrees, pivotX, pivotY))
                    sx = 1f
                    sy = 1f
                    tx = 0f
                    ty = 0f
                    degrees = 0f
                    pivotX = 0f
                    pivotY = 0f
                }
                is FormulaOp.DrawText -> text(op.text, x(op.x), y(op.y))
                is FormulaOp.DrawLine -> {
                    val path = PdfPath()
                    path.moveTo(map(x(op.x1), y(op.y1)))
                    path.lineTo(map(x(op.x2), y(op.y2)))
                    // As on a bitmap, a line is at least a pixel wide
                    shapes += FormulaShape(path, color, max(1f, sy * strokeWidth) * pageScale)
                }
                is FormulaOp.DrawRect -> {
                    val path = PdfPath().apply { addPolygon(rect(x(op.x), y(op.y), sx * op.width, sy * op.height)) }
                    shapes += if (op.fill) FormulaShape(path, color) else FormulaShape(path, color, sy * strokeWidth * pageScale)
                }
                is FormulaOp.DrawRoundRect -> {
                    val path = roundRect(x(op.x), y(op.y), sx * op.width, sy * op.height, sx * op.rx, sy * op.ry)
                    shapes += if (op.fill) FormulaShape(path, color) else FormulaShape(path, color, sy * strokeWidth * pageScale)
                }
            }
        }

        /** [text] from ([x], [y]) on its baseline, a glyph after the other (as a canvas draws a string). */
        private fun text(text: String, x: Float, y: Float) {
            val font = font
            val size = (font?.size ?: DEFAULT_SIZE) * sy
            val ttf = font?.let(fonts) ?: return
            if (size <= 0f || ttf.unitsPerEm <= 0) return
            val scale = size / ttf.unitsPerEm
            val scaleX = if (sy != 0f && sx != sy) sx / sy else 1f
            val skew = if (font.fakeItalic) FAKE_ITALIC_SKEW else 0f
            val path = PdfPath()
            var penX = x
            var i = 0
            while (i < text.length) {
                val codePoint = codePointAt(text, i)
                i += if (codePoint > 0xFFFF) 2 else 1
                val glyph = ttf.glyphId(codePoint)
                for (contour in ttf.outline(glyph)) {
                    addContour(path, contour.size, { k ->
                        val gx = contour.xs[k] * scale
                        val gy = contour.ys[k] * scale
                        map(penX + gx * scaleX + skew * gy, y - gy)
                    }, contour.onCurve)
                }
                penX += ttf.advance(glyph) * scale * scaleX
            }
            if (path.isEmpty) return
            shapes += FormulaShape(path, color)
            // The fake bold outsets the outlines (Skia: from 1/24 of the size at 9 px to 1/32 at 36 px)
            if (font.fakeBold) shapes += FormulaShape(path, color, 2f * fakeBoldOutset(size) * pageScale)
        }

        /** A closed contour of quadratic curves through [point] (two points off the curve imply one between them). */
        private fun addContour(path: PdfPath, count: Int, point: (Int) -> Pair<Float, Float>, onCurve: BooleanArray) {
            if (count == 0) return
            // Starting on the curve: the first point on it, or the middle of the first two
            val first = (0 until count).firstOrNull { onCurve[it] }
            val start = if (first != null) point(first) else mid(point(0), point(1 % count))
            val offset = first ?: 0
            path.moveTo(start)
            var current = start
            var control: Pair<Float, Float>? = null
            for (step in 1..count) {
                val k = (offset + step) % count
                val p = point(k)
                if (onCurve[k]) {
                    if (control == null) path.lineTo(p) else quad(path, current, control, p)
                    current = p
                    control = null
                } else {
                    if (control != null) {
                        val middle = mid(control, p)
                        quad(path, current, control, middle)
                        current = middle
                    }
                    control = p
                }
            }
            control?.let { quad(path, current, it, start) }
            path.close()
        }

        private fun quad(path: PdfPath, from: Pair<Float, Float>, control: Pair<Float, Float>, to: Pair<Float, Float>) {
            // The quadratic curve as a cubic one
            path.cubicTo(
                from.first + 2f / 3f * (control.first - from.first), from.second + 2f / 3f * (control.second - from.second),
                to.first + 2f / 3f * (control.first - to.first), to.second + 2f / 3f * (control.second - to.second),
                to.first, to.second
            )
        }

        private fun mid(a: Pair<Float, Float>, b: Pair<Float, Float>) = Pair((a.first + b.first) / 2f, (a.second + b.second) / 2f)

        /** A point of the device (after the scales and translations) on the page. */
        private fun map(x: Float, y: Float): Pair<Float, Float> {
            val onCanvas = canvas.mapPoint(x, y)
            val onPage = toPage.mapPoint(onCanvas.x, onCanvas.y)
            return Pair(onPage.x, onPage.y)
        }

        private fun rect(x: Float, y: Float, width: Float, height: Float): FloatArray {
            val corners = listOf(map(x, y), map(x + width, y), map(x + width, y + height), map(x, y + height))
            return FloatArray(8) { if (it % 2 == 0) corners[it / 2].first else corners[it / 2].second }
        }

        private fun roundRect(x: Float, y: Float, width: Float, height: Float, rx: Float, ry: Float): PdfPath {
            val k = 0.5522848f
            val path = PdfPath()
            path.moveTo(map(x + rx, y))
            path.lineTo(map(x + width - rx, y))
            path.cubicTo(map(x + width - rx + k * rx, y), map(x + width, y + ry - k * ry), map(x + width, y + ry))
            path.lineTo(map(x + width, y + height - ry))
            path.cubicTo(map(x + width, y + height - ry + k * ry), map(x + width - rx + k * rx, y + height), map(x + width - rx, y + height))
            path.lineTo(map(x + rx, y + height))
            path.cubicTo(map(x + rx - k * rx, y + height), map(x, y + height - ry + k * ry), map(x, y + height - ry))
            path.lineTo(map(x, y + ry))
            path.cubicTo(map(x, y + ry - k * ry), map(x + rx - k * rx, y), map(x + rx, y))
            path.close()
            return path
        }
    }

    private fun PdfPath.moveTo(p: Pair<Float, Float>) = moveTo(p.first, p.second)
    private fun PdfPath.lineTo(p: Pair<Float, Float>) = lineTo(p.first, p.second)
    private fun PdfPath.cubicTo(a: Pair<Float, Float>, b: Pair<Float, Float>, c: Pair<Float, Float>) =
        cubicTo(a.first, a.second, b.first, b.second, c.first, c.second)

    private companion object {
        /** Size of the characters without a font (as the graphics of the platforms). */
        const val DEFAULT_SIZE = 46f

        /** Slant of a synthesized italic (Skia). */
        const val FAKE_ITALIC_SKEW = 0.25f

        fun fakeBoldOutset(size: Float): Float {
            val t = ((size - 9f) / (36f - 9f)).coerceIn(0f, 1f)
            return size * (1f / 24f + t * (1f / 32f - 1f / 24f))
        }
    }
}

/** The code point of [text] at [index] (a surrogate pair read as one). */
internal fun codePointAt(text: String, index: Int): Int {
    val high = text[index]
    if (high.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
        return 0x10000 + ((high.code - 0xD800) shl 10) + (text[index + 1].code - 0xDC00)
    }
    return high.code
}

/** The code points of [text]. */
internal fun codePoints(text: String): List<Int> {
    val points = mutableListOf<Int>()
    var i = 0
    while (i < text.length) {
        val codePoint = codePointAt(text, i)
        points += codePoint
        i += if (codePoint > 0xFFFF) 2 else 1
    }
    return points
}

