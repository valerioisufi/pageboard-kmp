package io.nano.tex

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import kotlin.math.PI

/**
 * 2D graphics context replaying the TeX drawing commands on an Android [Canvas].
 *
 * The affine transformation is maintained manually ([t]): Android does not support scaling
 * the text drawn on hardware accelerated canvases reliably.
 */
class Graphics2D(val canvas: Canvas) {

    private companion object {
        const val CAP_BUTT = 0
        const val CAP_ROUND = 1
        const val CAP_SQUARE = 2

        const val JOIN_BEVEL = 0
        const val JOIN_MITER = 1
        const val JOIN_ROUND = 2

        const val SX = 0
        const val SY = 4
        const val TX = 2
        const val TY = 5
        const val R = 6
        const val PX = 7
        const val PY = 8
    }

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 46f }
    private var t = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f)
    private var colorLocked = false
    private val tmpRect = RectF()

    fun setFont(font: Font?) {
        if (font == null) {
            paint.typeface = Typeface.DEFAULT
            paint.textSize = 46f
        } else {
            paint.typeface = font.typeface
            paint.textSize = font.size
        }
    }

    fun lockColor() {
        colorLocked = true
    }

    fun unlockColor() {
        colorLocked = false
    }

    fun setColor(c: Int) {
        if (colorLocked) return
        paint.color = c
    }

    fun setStrokeWidth(width: Float) {
        paint.strokeWidth = width
    }

    fun setStroke(width: Float, miterLimit: Float, cap: Int, join: Int) {
        paint.strokeWidth = width
        paint.strokeMiter = miterLimit
        paint.strokeCap = when (cap) {
            CAP_ROUND -> Paint.Cap.ROUND
            CAP_SQUARE -> Paint.Cap.SQUARE
            else -> Paint.Cap.BUTT
        }
        paint.strokeJoin = when (join) {
            JOIN_BEVEL -> Paint.Join.BEVEL
            JOIN_ROUND -> Paint.Join.ROUND
            else -> Paint.Join.MITER
        }
    }

    fun translate(dx: Float, dy: Float) {
        t[TX] += t[SX] * dx
        t[TY] += t[SY] * dy
    }

    fun scale(sx: Float, sy: Float) {
        t[SX] *= sx
        t[SY] *= sy
    }

    fun rotate(angle: Float, px: Float, py: Float) {
        val r = (angle / PI * 180).toFloat()
        t[R] += r
        t[PX] = x(px)
        t[PY] = y(py)
        canvas.rotate(r, t[PX], t[PY])
    }

    fun reset() {
        val r = t[R]
        val px = t[PX]
        val py = t[PY]
        t = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f)
        canvas.rotate(-r, px, py)
    }

    fun sx(): Float = t[SX]
    fun sy(): Float = t[SY]

    private fun x(x: Float): Float = x * t[SX] + t[TX]
    private fun y(y: Float): Float = y * t[SY] + t[TY]
    private fun w(w: Float): Float = t[SX] * w
    private fun h(h: Float): Float = t[SY] * h

    fun drawChar(c: Char, x: Float, y: Float) {
        drawText(c.toString(), x, y)
    }

    fun drawText(txt: String, x: Float, y: Float) {
        val size = paint.textSize
        val scaleX = paint.textScaleX
        paint.textSize = size * sy()
        if (sy() != 0f) paint.textScaleX = sx() / sy()
        paint.style = Paint.Style.FILL
        canvas.drawText(txt, x(x), y(y), paint)
        paint.textSize = size
        paint.textScaleX = scaleX
    }

    fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float) {
        val th = paint.strokeWidth
        paint.strokeWidth = h(th).coerceAtLeast(1f)
        canvas.drawLine(x(x1), y(y1), x(x2), y(y2), paint)
        paint.strokeWidth = th
    }

    private fun renderRect(x: Float, y: Float, w: Float, h: Float, style: Paint.Style) {
        val oldStyle = paint.style
        val th = paint.strokeWidth
        paint.style = style
        paint.strokeWidth = h(th)
        val xx = x(x)
        val yy = y(y)
        canvas.drawRect(xx, yy, xx + w(w), yy + h(h), paint)
        paint.style = oldStyle
        paint.strokeWidth = th
    }

    fun drawRect(x: Float, y: Float, w: Float, h: Float) = renderRect(x, y, w, h, Paint.Style.STROKE)

    fun fillRect(x: Float, y: Float, w: Float, h: Float) = renderRect(x, y, w, h, Paint.Style.FILL)

    private fun renderRoundRect(x: Float, y: Float, w: Float, h: Float, rx: Float, ry: Float, style: Paint.Style) {
        val oldStyle = paint.style
        val th = paint.strokeWidth
        paint.style = style
        paint.strokeWidth = h(th)
        tmpRect.left = x(x)
        tmpRect.top = y(y)
        tmpRect.right = tmpRect.left + w(w)
        tmpRect.bottom = tmpRect.top + h(h)
        canvas.drawRoundRect(tmpRect, w(rx), h(ry), paint)
        paint.style = oldStyle
        paint.strokeWidth = th
    }

    fun drawRoundRect(x: Float, y: Float, w: Float, h: Float, rx: Float, ry: Float) =
        renderRoundRect(x, y, w, h, rx, ry, Paint.Style.STROKE)

    fun fillRoundRect(x: Float, y: Float, w: Float, h: Float, rx: Float, ry: Float) =
        renderRoundRect(x, y, w, h, rx, ry, Paint.Style.FILL)
}
