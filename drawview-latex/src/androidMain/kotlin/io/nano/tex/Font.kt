package io.nano.tex

import android.graphics.Typeface
import io.github.valerioisufi.drawview.core.platform.PlatformFiles
import io.github.valerioisufi.drawview.latex.TexFont
import io.github.valerioisufi.drawview.latex.TexTextFonts

/**
 * Font used by the TeX engine (created and derived from native code through JNI).
 *
 * @property style bold (1) and italic (2), synthesized by Android over a face that is not.
 * @property file the TrueType file of the TeX resources, or `null` for a font of the text.
 */
class Font private constructor(
    val typeface: Typeface,
    val size: Float,
    val style: Int = 0,
    val file: String? = null,
    /** Whether [typeface] is a face of [style] (not synthesized). */
    private val styledFace: Boolean = false
) {
    fun deriveFont(style: Int): Font {
        if (typeface.style == style) return this
        return Font(Typeface.create(typeface, style), size, style, file)
    }

    /** This font for a [TexDrawing][io.github.valerioisufi.drawview.latex.TexDrawing]. */
    fun toTexFont(): TexFont {
        val bold = style and TexFont.BOLD != 0
        val italic = style and TexFont.ITALIC != 0
        return if (file != null) {
            TexFont(file, style, size, fakeBold = bold, fakeItalic = italic)
        } else {
            TexFont(null, style, size, fakeBold = false, fakeItalic = false)
        }
    }

    companion object {
        private val fileCache = mutableMapOf<String, Typeface>()
        private val textCache = mutableMapOf<Int, Typeface?>()

        @JvmStatic
        fun create(name: String, style: Int, size: Float): Font {
            // The fonts of the text of the formulas, when the application gives them: the style
            // synthesized over the regular one when it does not give it
            val wanted = style and (TexFont.BOLD or TexFont.ITALIC)
            textTypeface(wanted)?.let { return Font(it, size, wanted, styledFace = true) }
            textTypeface(0)?.let { return Font(Typeface.create(it, wanted), size, wanted) }
            return Font(Typeface.create(name, style), size, wanted)
        }

        @JvmStatic
        fun create(file: String, size: Float): Font {
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
