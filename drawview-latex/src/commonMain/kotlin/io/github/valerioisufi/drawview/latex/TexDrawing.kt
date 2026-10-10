package io.github.valerioisufi.drawview.latex

import io.github.valerioisufi.drawview.platform.SyncLock
import io.github.valerioisufi.drawview.platform.syncLock

/**
 * A formula as MicroTeX draws it, the same on every platform (see [LatexEngine.drawing]): the
 * [ops] drawing it on [width] x [height] pixels (at the size it was laid out at), its baseline at
 * [baselineRatio] of its height.
 */
class TexDrawing(val width: Int, val height: Int, val baselineRatio: Float, val ops: List<TexOp>)

/**
 * A font of a formula, [size] pixels: a TrueType [file] of the TeX resources, or the font of the
 * text of the formulas (`\text{}`, see [LatexEngine.setTextFonts]) when [file] is `null`. [style]
 * is bold (1) and italic (2); [fakeBold] and [fakeItalic] are synthesized over a font that is not.
 */
class TexFont(val file: String?, val style: Int, val size: Float, val fakeBold: Boolean, val fakeItalic: Boolean) {
    companion object {
        const val BOLD = 1
        const val ITALIC = 2
    }
}

/**
 * An operation of a [TexDrawing], as MicroTeX gives it to its graphics: the coordinates are
 * transformed by the translations and the scales (applied to them), the rotations turn what
 * follows around their point until a [Reset].
 */
sealed interface TexOp {
    class SetFont(val font: TexFont?) : TexOp
    class SetColor(val argb: Int) : TexOp
    class SetStrokeWidth(val width: Float) : TexOp
    class SetStroke(val width: Float, val miterLimit: Float, val cap: Int, val join: Int) : TexOp
    class Translate(val dx: Float, val dy: Float) : TexOp
    class Scale(val sx: Float, val sy: Float) : TexOp

    /** A rotation of [angle] radians around ([px], [py]). */
    class Rotate(val angle: Float, val px: Float, val py: Float) : TexOp
    data object Reset : TexOp

    /** [text] in the current font, from ([x], [y]) on its baseline. */
    class DrawText(val text: String, val x: Float, val y: Float) : TexOp
    class DrawLine(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : TexOp
    class DrawRect(val x: Float, val y: Float, val width: Float, val height: Float, val fill: Boolean) : TexOp
    class DrawRoundRect(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val rx: Float,
        val ry: Float,
        val fill: Boolean
    ) : TexOp

    companion object {
        const val CAP_BUTT = 0
        const val CAP_ROUND = 1
        const val CAP_SQUARE = 2
        const val JOIN_BEVEL = 0
        const val JOIN_MITER = 1
        const val JOIN_ROUND = 2
    }
}

/** The TrueType files of the text of the formulas, by style (see [LatexEngine.setTextFonts]). */
internal object TexTextFonts {
    private val lock = SyncLock()
    private val fonts = mutableMapOf<Int, ByteArray>()

    fun set(byStyle: Map<Int, ByteArray>) = syncLock(lock) {
        fonts.clear()
        fonts.putAll(byStyle)
    }

    /** The font of [style] (bold and italic bits), or `null`. */
    fun get(style: Int): ByteArray? = syncLock(lock) { fonts[style] }
}
