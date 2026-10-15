package io.github.valerioisufi.drawview.model.geometry

import kotlinx.serialization.Serializable

/**
 * An immutable 2D affine transformation (same convention as [Matrix2D]), the value kept in the
 * model and exchanged in the changes of a document.
 */
@Serializable
public data class AffineTransform(
    val a: Float = 1f,
    val b: Float = 0f,
    val c: Float = 0f,
    val d: Float = 1f,
    val tx: Float = 0f,
    val ty: Float = 0f
) {
    val isIdentity: Boolean get() = this == IDENTITY

    public fun toMatrix(): Matrix2D = Matrix2D(a, b, c, d, tx, ty)

    /** This transformation followed by [after]. */
    public fun then(after: Matrix2D): AffineTransform = of(toMatrix().postConcat(after))

    public companion object {
        public val IDENTITY: AffineTransform = AffineTransform()

        public fun of(matrix: Matrix2D): AffineTransform =
            AffineTransform(matrix.a, matrix.b, matrix.c, matrix.d, matrix.tx, matrix.ty)
    }
}
