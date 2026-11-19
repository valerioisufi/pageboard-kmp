package io.nano.tex

import kotlin.jvm.JvmStatic

internal class TextLayout {
    companion object {
        /** Keep in sync with the size of TextRenderingBox::_font (MicroTeX). */
        private const val FACTORED_TEXT_SIZE = 10f

        @JvmStatic
        fun getBounds(txt: String, font: Font): Rect {
            return try {
                val bounds = font.toSkiaFont(FACTORED_TEXT_SIZE).measureText(txt)
                Rect(bounds.left, bounds.top, bounds.width, bounds.height)
            } catch (_: Throwable) {
                Rect()
            }
        }
    }
}
