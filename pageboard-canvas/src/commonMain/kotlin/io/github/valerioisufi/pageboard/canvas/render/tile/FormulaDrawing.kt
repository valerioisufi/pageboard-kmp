package io.github.valerioisufi.pageboard.canvas.render.tile

/**
 * A formula as its engine draws it, the same on every platform (see [FormulaRenderer.drawing]),
 * e.g. to write it as vectors: the [ops] drawing it on [width] x [height] pixels (at the size it was laid out at), its baseline at
 * [baselineRatio] of its height.
 */
public class FormulaDrawing(public val width: Int, public val height: Int, public val baselineRatio: Float, public val ops: List<FormulaOp>)

/**
 * A font of a formula, [size] pixels: a TrueType [file] of the resources of the engine, or the
 * font of the text of the formulas (`\text{}`, see [FormulaRenderer.prepare]) when [file] is
 * `null`. [style]
 * is bold (1) and italic (2); [fakeBold] and [fakeItalic] are synthesized over a font that is not.
 */
public class FormulaFont(public val file: String?, public val style: Int, public val size: Float, public val fakeBold: Boolean, public val fakeItalic: Boolean) {
    public companion object {
        public const val BOLD: Int = 1
        public const val ITALIC: Int = 2
    }
}

/**
 * An operation of a [FormulaDrawing], as the engine (MicroTeX) gives it to its graphics: the coordinates are
 * transformed by the translations and the scales (applied to them), the rotations turn what
 * follows around their point until a [Reset].
 */
public sealed interface FormulaOp {
    public class SetFont(public val font: FormulaFont?) : FormulaOp
    public class SetColor(public val argb: Int) : FormulaOp
    public class SetStrokeWidth(public val width: Float) : FormulaOp
    public class SetStroke(public val width: Float, internal val miterLimit: Float, public val cap: Int, public val join: Int) : FormulaOp
    public class Translate(public val dx: Float, public val dy: Float) : FormulaOp
    public class Scale(public val sx: Float, public val sy: Float) : FormulaOp

    /** A rotation of [angle] radians around ([px], [py]). */
    public class Rotate(public val angle: Float, public val px: Float, public val py: Float) : FormulaOp
    public data object Reset : FormulaOp

    /** [text] in the current font, from ([x], [y]) on its baseline. */
    public class DrawText(public val text: String, public val x: Float, public val y: Float) : FormulaOp
    public class DrawLine(public val x1: Float, public val y1: Float, public val x2: Float, public val y2: Float) : FormulaOp
    public class DrawRect(public val x: Float, public val y: Float, public val width: Float, public val height: Float, public val fill: Boolean) : FormulaOp
    public class DrawRoundRect(
        public val x: Float,
        public val y: Float,
        public val width: Float,
        public val height: Float,
        public val rx: Float,
        public val ry: Float,
        public val fill: Boolean
    ) : FormulaOp

    public companion object {
        internal const val CAP_BUTT = 0
        internal const val CAP_ROUND = 1
        internal const val CAP_SQUARE = 2
        internal const val JOIN_BEVEL = 0
        internal const val JOIN_MITER = 1
        internal const val JOIN_ROUND = 2
    }
}
