package io.github.valerioisufi.pageboard.canvas.render

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.skiaCanvas
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Paint
import org.jetbrains.skia.TextLine
import org.jetbrains.skia.Typeface

private val defaultShapeLabelTypeface: Typeface? by lazy {
    FontMgr.default.matchFamilyStyle(null, FontStyle.NORMAL)
}

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
    val maxTextWidth = (widthMm * 0.88f).coerceAtLeast(1f)
    val fontSize = fontSizeMm.coerceIn(1.5f, heightMm * 0.8f)
    val font = Font(defaultShapeLabelTypeface, fontSize)
    val paint = Paint().apply {
        color = colorArgb
        isAntiAlias = true
    }
    try {
        val rawLines = text.split('\n')
        val wrapped = ArrayList<String>(rawLines.size)
        for (raw in rawLines) {
            if (raw.isEmpty()) {
                wrapped += ""
                continue
            }
            val words = raw.split(' ')
            var current = ""
            for (word in words) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (current.isNotEmpty() && font.measureTextWidth(candidate) > maxTextWidth) {
                    wrapped += current
                    current = word
                } else {
                    current = candidate
                }
            }
            if (current.isNotEmpty()) wrapped += current
        }
        val lineHeight = fontSize * 1.25f
        val totalHeight = wrapped.size * lineHeight
        val centerY = if (isLine) heightMm * 0.5f - fontSize * 0.85f else heightMm * 0.5f
        var y = centerY - totalHeight * 0.5f + fontSize * 0.85f
        val skia = canvas.skiaCanvas
        for (line in wrapped) {
            val textLine = TextLine.make(line, font)
            try {
                val x = (widthMm - textLine.width) * 0.5f
                skia.drawTextLine(textLine, x, y, paint)
            } finally {
                textLine.close()
            }
            y += lineHeight
        }
    } finally {
        font.close()
        paint.close()
    }
}
