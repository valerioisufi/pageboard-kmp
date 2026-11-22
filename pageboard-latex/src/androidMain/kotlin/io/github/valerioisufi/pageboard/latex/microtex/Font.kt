package io.github.valerioisufi.pageboard.latex.microtex

import android.graphics.Typeface
import io.github.valerioisufi.pageboard.canvas.render.tile.FormulaFont
import io.github.valerioisufi.pageboard.latex.TexTextFonts
import io.github.valerioisufi.pageboard.platform.PlatformFiles

/**
 * Font used by the TeX engine (created and derived from native code through JNI).
 *
 * @property style bold (1) and italic (2), synthesized by Android over a face that is not.
 * @property file the TrueType file of the TeX resources, or `null` for a font of the text.
 */
internal class Font private constructor(
    public val typeface: Typeface,
    public val size: Float,
    public val style: Int = 0,
    public val file: String? = null
) {
    public fun deriveFont(style: Int): Font {
        if (typeface.style == style) return this
        return Font(Typeface.create(typeface, style), size, style, file)
    }

    /** This font for a [io.github.valerioisufi.pageboard.canvas.render.tile.FormulaDrawing][io.github.valerioisufi.pageboard.canvas.render.tile.FormulaDrawing]. */
    public fun toTexFont(): FormulaFont {
        val bold = style and FormulaFont.BOLD != 0
        val italic = style and FormulaFont.ITALIC != 0
        return if (file != null) {
            FormulaFont(file, style, size, fakeBold = bold, fakeItalic = italic)
        } else {
            FormulaFont(null, style, size, fakeBold = false, fakeItalic = false)
        }
    }

    public companion object {
        private val fileCache = mutableMapOf<String, Typeface>()
        private val textCache = mutableMapOf<Int, Typeface?>()

        @JvmStatic
        public fun create(name: String, style: Int, size: Float): Font {
            // The fonts of the text of the formulas, when the application gives them: the style
            // synthesized over the regular one when it does not give it
            val wanted = style and (FormulaFont.BOLD or FormulaFont.ITALIC)
            textTypeface(wanted)?.let { return Font(it, size, wanted) }
            textTypeface(0)?.let { return Font(Typeface.create(it, wanted), size, wanted) }
            return Font(Typeface.create(name, style), size, wanted)
        }

        @JvmStatic
        public fun create(file: String, size: Float): Font {
            val typeface = synchronized(fileCache) {
                fileCache.getOrPut(file) {
                    try {
                        Typeface.createFromFile(file)
                    } catch (_: Throwable) {
                        Typeface.SERIF
                    }
                }
            }
            return Font(typeface, size, file = file)
        }

        /** The typeface of the text of the formulas of [style] (written once to a file), or `null`. */
        private fun textTypeface(style: Int): Typeface? {
            val bytes = TexTextFonts.get(style) ?: return null
            return synchronized(textCache) {
                textCache.getOrPut(style) {
                    try {
                        val path = PlatformFiles.resourcesDirectory().trimEnd('/') + "/tex-text-$style.ttf"
                        if (!PlatformFiles.writeBytes(path, bytes)) null else Typeface.createFromFile(path)
                    } catch (_: Throwable) {
                        null
                    }
                }
            }
        }
    }
}
