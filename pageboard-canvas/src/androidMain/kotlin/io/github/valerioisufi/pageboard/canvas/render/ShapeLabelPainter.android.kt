package io.github.valerioisufi.pageboard.canvas.render

import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.ceil

internal actual fun drawShapeLabel(
    canvas: Canvas,
    text: String,
    colorArgb: Int,
    fontSizeMm: Float,
    widthMm: Float,
    heightMm: Float,
    isLine: Boolean
) {
    if (text.isBlank() || widthMm <= 0f || heightMm <= 0f) return
    // Android StaticLayout takes integer pixel widths; scale coordinates so millimeter text renders sharply
    val scale = 10f
    val maxTextWidth = (widthMm * 0.88f * scale).let { ceil(it.toDouble()).toInt() }.coerceAtLeast(1)
    val fontSizePx = fontSizeMm.coerceIn(1.5f, heightMm * 0.8f) * scale
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorArgb
        textSize = fontSizePx
    }
    val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, maxTextWidth)
        .setAlignment(Layout.Alignment.ALIGN_CENTER)
        .setIncludePad(false)
        .build()
    val native = canvas.nativeCanvas
    val save = native.save()
    try {
        native.scale(1f / scale, 1f / scale)
        val totalHeight = layout.height.toFloat()
        val centerY = if (isLine) (heightMm * 0.5f - fontSizeMm * 0.85f) * scale else heightMm * 0.5f * scale
        val dx = (widthMm * scale - maxTextWidth) * 0.5f
        val dy = centerY - totalHeight * 0.5f
        native.translate(dx, dy)
        layout.draw(native)
    } finally {
        native.restoreToCount(save)
    }
}
