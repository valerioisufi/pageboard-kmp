package io.github.valerioisufi.pageboard.latex.microtex

import io.github.valerioisufi.pageboard.canvas.render.tile.FormulaFont
import io.github.valerioisufi.pageboard.latex.TexTextFonts
import io.github.valerioisufi.pageboard.platform.SyncLock
import io.github.valerioisufi.pageboard.platform.syncLock
import kotlin.jvm.JvmStatic
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Typeface

/**
 * Font used by the TeX engine, backed by a Skia [Typeface].
 *
 * @property style TeX style bits (1 = bold, 2 = italic), synthesized when the typeface itself
 * is not bold/italic (like `Typeface.create(typeface, style)` on Android).
 */
internal class Font(
    public val typeface: Typeface?,
    public val size: Float,
    public val style: Int = 0,
    /** The TrueType file of the TeX resources, or `null` for a font of the text. */
    public val file: String? = null
) {
    public fun deriveFont(style: Int): Font = if (style == this.style) this else Font(typeface, size, style, file)

    /** This font for a [io.github.valerioisufi.pageboard.canvas.render.tile.FormulaDrawing]: the style synthesized over the file as [toSkiaFont] does. */
    public fun toTexFont(): FormulaFont {
        val tfStyle = typeface?.fontStyle
        val boldFace = tfStyle != null && tfStyle.weight >= 600
        val italicFace = tfStyle != null && tfStyle.slant != org.jetbrains.skia.FontSlant.UPRIGHT
        val bold = style and BOLD != 0
        val italic = style and ITALIC != 0
        return if (file != null) {
            FormulaFont(file, style, size, fakeBold = bold && !boldFace, fakeItalic = italic && !italicFace)
        } else {
            val textStyle = (if (bold || boldFace) FormulaFont.BOLD else 0) or (if (italic || italicFace) FormulaFont.ITALIC else 0)
            FormulaFont(null, textStyle, size, fakeBold = false, fakeItalic = false)
        }
    }

    /**
     * Skia font at [pixelSize] with the synthetic style applied.
     */
    public fun toSkiaFont(pixelSize: Float): org.jetbrains.skia.Font {
        val font = org.jetbrains.skia.Font(typeface ?: defaultTypeface, pixelSize)
        val tfStyle = typeface?.fontStyle
        if (style and BOLD != 0 && (tfStyle == null || tfStyle.weight < 600)) font.isEmboldened = true
        if (style and ITALIC != 0 && (tfStyle == null || tfStyle.slant == org.jetbrains.skia.FontSlant.UPRIGHT)) font.skewX = -0.25f
        return font
    }

    public companion object {
        public const val BOLD: Int = 1
        public const val ITALIC: Int = 2

        private val lock = SyncLock()
        private val fileCache = mutableMapOf<String, Typeface?>()

        private val defaultTypeface: Typeface? by lazy {
            FontMgr.default.matchFamilyStyle(null, FontStyle.NORMAL)
        }

        private fun skiaStyle(style: Int): FontStyle = when (style and (BOLD or ITALIC)) {
            BOLD -> FontStyle.BOLD
            ITALIC -> FontStyle.ITALIC
            BOLD or ITALIC -> FontStyle.BOLD_ITALIC
            else -> FontStyle.NORMAL
        }

        private val textCache = mutableMapOf<Int, Typeface?>()

        /** The typeface of the text of the formulas of [style] (see [TexTextFonts]), or `null`. */
        private fun textTypeface(style: Int): Typeface? {
            val bytes = TexTextFonts.get(style) ?: return null
            return syncLock(lock) {
                textCache.getOrPut(style) {
                    try {
                        FontMgr.default.makeFromData(Data.makeFromBytes(bytes))
                    } catch (_: Throwable) {
                        null
                    }
                }
            }
        }

        /** Creates a font from a system family name (used for \text{} blocks). */
        @JvmStatic
        public fun create(name: String, style: Int, size: Float): Font {
            // The fonts of the text of the formulas, when the application gives them: the style
            // synthesized over the regular one when it does not give it
            val wanted = style and (BOLD or ITALIC)
            textTypeface(wanted)?.let { return Font(it, size, 0) }
            textTypeface(0)?.let { return Font(it, size, wanted) }
            val typeface = try {
                FontMgr.default.matchFamilyStyle(name, skiaStyle(style))
                    ?: FontMgr.default.matchFamilyStyle(null, skiaStyle(style))
            } catch (_: Throwable) {
                null
            }
            // The style is already part of the matched typeface
            return Font(typeface, size, 0)
        }

        /** Creates a font from a font file of the TeX resources. */
        @JvmStatic
        public fun create(file: String, size: Float): Font {
            val typeface = syncLock(lock) {
                fileCache.getOrPut(file) {
                    try {
                        FontMgr.default.makeFromFile(file, 0)
                    } catch (_: Throwable) {
                        null
                    }
                }
            }
            return Font(typeface, size, 0, file)
        }
    }
}
