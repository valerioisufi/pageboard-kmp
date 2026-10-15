package io.nano.tex

import kotlin.math.PI
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.PaintStrokeJoin
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect as SkiaRect

/**
 * 2D graphics context replaying the TeX drawing commands on a Skia canvas.
 *
 * Like the Android implementation, the affine transformation is maintained manually ([t]) and
 * applied to the coordinates, rotations excepted.
 */
public class Graphics2D(public val canvas: Canvas) {

    public companion object {
        public const val CAP_BUTT: Int = 0
        public const val CAP_ROUND: Int = 1
        public const val CAP_SQUARE: Int = 2

        public const val JOIN_BEVEL: Int = 0
        public const val JOIN_MITER: Int = 1
        public const val JOIN_ROUND: Int = 2

        private const val SX = 0
        private const val SY = 4
        private const val TX = 2
        private const val TY = 5
        private const val R = 6
        private const val PX = 7
        private const val PY = 8
    }

    private val paint = Paint().apply {
        isAntiAlias = true
    }

    private var currentFont: Font? = null
    private var colorLocked = false

    // Transform matrix
    private val t = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 0f
    )

    public fun sx(): Float = t[SX]
    public fun sy(): Float = t[SY]
    public fun tx(): Float = t[TX]
    public fun ty(): Float = t[TY]

    public fun x(x: Float): Float = x * t[SX] + t[TX]
    public fun y(y: Float): Float = y * t[SY] + t[TY]
    public fun w(w: Float): Float = t[SX] * w
    public fun h(h: Float): Float = t[SY] * h

    public fun setFont(font: Font?) {
        currentFont = font
    }

    public fun lockColor() {
        colorLocked = true
    }

    public fun unlockColor() {
        colorLocked = false
    }

    public fun setColor(color: Int) {
        if (colorLocked) return
        paint.color = color
    }

    public fun setStrokeWidth(width: Float) {
        paint.strokeWidth = width
    }

    public fun setStroke(width: Float, miterLimit: Float, cap: Int, join: Int) {
        paint.strokeWidth = width
        paint.strokeMiter = miterLimit
        paint.strokeCap = when (cap) {
            CAP_ROUND -> PaintStrokeCap.ROUND
            CAP_SQUARE -> PaintStrokeCap.SQUARE
            else -> PaintStrokeCap.BUTT
        }
        paint.strokeJoin = when (join) {
            JOIN_ROUND -> PaintStrokeJoin.ROUND
            JOIN_BEVEL -> PaintStrokeJoin.BEVEL
            else -> PaintStrokeJoin.MITER
        }
    }

    public fun translate(dx: Float, dy: Float) {
        t[TX] += t[SX] * dx
        t[TY] += t[SY] * dy
    }

    public fun scale(sx: Float, sy: Float) {
        t[SX] *= sx
        t[SY] *= sy
    }

    public fun rotate(angle: Float, px: Float, py: Float) {
        val deg = (angle / PI * 180).toFloat()
        t[R] += deg
        t[PX] = x(px)
        t[PY] = y(py)
        canvas.rotate(deg, t[PX], t[PY])
    }

    public fun reset() {
        val deg = t[R]
        val px = t[PX]
        val py = t[PY]
        t[0] = 1f; t[1] = 0f; t[2] = 0f
        t[3] = 0f; t[4] = 1f; t[5] = 0f
        t[6] = 0f; t[7] = 0f; t[8] = 0f
        if (deg != 0f) {
            canvas.rotate(-deg, px, py)
        }
    }

    public fun drawChar(c: Char, x: Float, y: Float) {
        drawText(c.toString(), x, y)
    }

    public fun drawText(txt: String, x: Float, y: Float) {
        val font = currentFont
        val size = (font?.size ?: 46f) * sy()
        val skFont = font?.toSkiaFont(size) ?: Font(null, size).toSkiaFont(size)
        if (sy() != 0f && sx() != sy()) skFont.scaleX = sx() / sy()
        paint.mode = PaintMode.FILL
        canvas.drawString(txt, x(x), y(y), skFont, paint)
    }

    public fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float) {
        val origWidth = paint.strokeWidth
        var sw = h(origWidth)
        if (sw < 1f) sw = 1f
        paint.strokeWidth = sw
        paint.mode = PaintMode.STROKE
        canvas.drawLine(x(x1), y(y1), x(x2), y(y2), paint)
        paint.strokeWidth = origWidth
    }

    public fun drawRect(x: Float, y: Float, w: Float, h: Float) {
        val origWidth = paint.strokeWidth
        paint.strokeWidth = h(origWidth)
        paint.mode = PaintMode.STROKE
        canvas.drawRect(SkiaRect.makeXYWH(x(x), y(y), w(w), h(h)), paint)
        paint.strokeWidth = origWidth
    }

    public fun fillRect(x: Float, y: Float, w: Float, h: Float) {
        paint.mode = PaintMode.FILL
        canvas.drawRect(SkiaRect.makeXYWH(x(x), y(y), w(w), h(h)), paint)
    }

    public fun drawRoundRect(x: Float, y: Float, w: Float, h: Float, rx: Float, ry: Float) {
        val origWidth = paint.strokeWidth
        paint.strokeWidth = h(origWidth)
        paint.mode = PaintMode.STROKE
        canvas.drawRRect(RRect.makeXYWH(x(x), y(y), w(w), h(h), w(rx), h(ry)), paint)
        paint.strokeWidth = origWidth
    }

    public fun fillRoundRect(x: Float, y: Float, w: Float, h: Float, rx: Float, ry: Float) {
        paint.mode = PaintMode.FILL
        canvas.drawRRect(RRect.makeXYWH(x(x), y(y), w(w), h(h), w(rx), h(ry)), paint)
    }
}
