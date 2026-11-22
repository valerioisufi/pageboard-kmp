package io.github.valerioisufi.pageboard.latex.microtex

import android.text.TextPaint

internal class TextLayout {
    companion object {
        /** Keep in sync with the size of TextRenderingBox::_font (MicroTeX). */
        private const val FACTORED_TEXT_SIZE = 10f

        private val paint = TextPaint().apply { textSize = FACTORED_TEXT_SIZE }
        private val bounds = android.graphics.Rect()

        @JvmStatic
        fun getBounds(txt: String, font: Font): Rect = synchronized(paint) {
            paint.typeface = font.typeface
            paint.getTextBounds(txt, 0, txt.length, bounds)
            Rect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.width().toFloat(), bounds.height().toFloat())
        }
    }
}
