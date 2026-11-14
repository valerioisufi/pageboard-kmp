package io.github.valerioisufi.drawview.latex

import io.github.valerioisufi.drawview.platform.SyncLock
import io.github.valerioisufi.drawview.platform.syncLock

/**
 * A formula as MicroTeX draws it, the same on every platform (see [LatexEngine.drawing]): the
 * [ops] drawing it on [width] x [height] pixels (at the size it was laid out at), its baseline at
 * [baselineRatio] of its height.
 */
public class TexDrawing(public val width: Int, public val height: Int, public val baselineRatio: Float, public val ops: List<TexOp>)

/**
 * A font of a formula, [size] pixels: a TrueType [file] of the TeX resources, or the font of the
 * text of the formulas (`\text{}`, see [LatexEngine.prepare]) when [file] is `null`. [style]
 * is bold (1) and italic (2); [fakeBold] and [fakeItalic] are synthesized over a font that is not.
 */
public class TexFont(public val file: String?, public val style: Int, public val size: Float, public val fakeBold: Boolean, public val fakeItalic: Boolean) {
    public companion object {
        public const val BOLD: Int = 1
        public const val ITALIC: Int = 2
    }
}

/**
 * An operation of a [TexDrawing], as MicroTeX gives it to its graphics: the coordinates are
 * transformed by the translations and the scales (applied to them), the rotations turn what
 * follows around their point until a [Reset].
 */
public sealed interface TexOp {
    public class SetFont(public val font: TexFont?) : TexOp
    public class SetColor(public val argb: Int) : TexOp
    public class SetStrokeWidth(public val width: Float) : TexOp
    public class SetStroke(public val width: Float, internal val miterLimit: Float, public val cap: Int, public val join: Int) : TexOp
    public class Translate(public val dx: Float, public val dy: Float) : TexOp
    public class Scale(public val sx: Float, public val sy: Float) : TexOp

    /** A rotation of [angle] radians around ([px], [py]). */
    public class Rotate(public val angle: Float, public val px: Float, public val py: Float) : TexOp
    public data object Reset : TexOp

    /** [text] in the current font, from ([x], [y]) on its baseline. */
    public class DrawText(public val text: String, public val x: Float, public val y: Float) : TexOp
    public class DrawLine(public val x1: Float, public val y1: Float, public val x2: Float, public val y2: Float) : TexOp
    public class DrawRect(public val x: Float, public val y: Float, public val width: Float, public val height: Float, public val fill: Boolean) : TexOp
    public class DrawRoundRect(
        public val x: Float,
        public val y: Float,
        public val width: Float,
        public val height: Float,
        public val rx: Float,
        public val ry: Float,
        public val fill: Boolean
    ) : TexOp

    public companion object {
        internal const val CAP_BUTT = 0
        internal const val CAP_ROUND = 1
        internal const val CAP_SQUARE = 2
        internal const val JOIN_BEVEL = 0
        internal const val JOIN_MITER = 1
        internal const val JOIN_ROUND = 2
    }
}

/** The TrueType files of the text of the formulas, by style (see [LatexEngine.prepare]). */
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
