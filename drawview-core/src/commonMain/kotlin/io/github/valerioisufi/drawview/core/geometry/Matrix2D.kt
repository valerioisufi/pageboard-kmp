package io.github.valerioisufi.drawview.core.geometry

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * High-performance, pure Kotlin Multiplatform 2D Affine Transformation Matrix (3x3).
 *
 * Encapsulates 2D transformations: translation, scale, rotation, and skew.
 * Compatible with standard computer graphics conventions:
 *
 * ```
 * [ a   c   tx ]   [ x ]   [ a*x + c*y + tx ]
 * [ b   d   ty ] * [ y ] = [ b*x + d*y + ty ]
 * [ 0   0    1 ]   [ 1 ]   [        1       ]
 * ```
 */
class Matrix2D(
    var a: Float = 1f,
    var b: Float = 0f,
    var c: Float = 0f,
    var d: Float = 1f,
    var tx: Float = 0f,
    var ty: Float = 0f
) {
    constructor(src: Matrix2D) : this(src.a, src.b, src.c, src.d, src.tx, src.ty)

    /**
     * Resets this matrix to the identity matrix.
     */
    fun reset(): Matrix2D {
        a = 1f; b = 0f
        c = 0f; d = 1f
        tx = 0f; ty = 0f
        return this
    }

    /**
     * Sets this matrix to match the values of [other].
     */
    fun set(other: Matrix2D): Matrix2D {
        a = other.a; b = other.b
        c = other.c; d = other.d
        tx = other.tx; ty = other.ty
        return this
    }

    /**
     * Pre-concatenates this matrix with a translation: M' = M * T.
     */
    fun preTranslate(dx: Float, dy: Float): Matrix2D {
        tx += a * dx + c * dy
        ty += b * dx + d * dy
        return this
    }

    /**
     * Post-concatenates this matrix with a translation: M' = T * M.
     */
    fun postTranslate(dx: Float, dy: Float): Matrix2D {
        tx += dx
        ty += dy
        return this
    }

    /**
     * Pre-concatenates this matrix with a scale: M' = M * S.
     */
    fun preScale(sx: Float, sy: Float): Matrix2D {
        a *= sx
        b *= sx
        c *= sy
        d *= sy
        return this
    }

    /**
     * Post-concatenates this matrix with a scale: M' = S * M.
     */
    fun postScale(sx: Float, sy: Float): Matrix2D {
        a *= sx
        c *= sx
        tx *= sx
        b *= sy
        d *= sy
        ty *= sy
        return this
    }

    /**
     * Post-concatenates this matrix with a scale around a pivot point (px, py).
     */
    fun postScale(sx: Float, sy: Float, px: Float, py: Float): Matrix2D {
        postTranslate(-px, -py)
        postScale(sx, sy)
        postTranslate(px, py)
        return this
    }

    /**
     * Post-concatenates this matrix with a rotation of [degrees] around the pivot ([px], [py]).
     */
    fun postRotate(degrees: Float, px: Float = 0f, py: Float = 0f): Matrix2D {
        val rad = degrees * PI.toFloat() / 180f
        val cosA = cos(rad)
        val sinA = sin(rad)
        // R = T(p) * Rot * T(-p)
        val rot = Matrix2D(
            a = cosA, b = sinA,
            c = -sinA, d = cosA,
            tx = px - cosA * px + sinA * py,
            ty = py - sinA * px - cosA * py
        )
        return postConcat(rot)
    }

    /**
     * Pre-concatenates this matrix with another matrix: M' = this * other.
     */
    fun preConcat(other: Matrix2D): Matrix2D {
        val newA = a * other.a + c * other.b
        val newB = b * other.a + d * other.b
        val newC = a * other.c + c * other.d
        val newD = b * other.c + d * other.d
        val newTx = a * other.tx + c * other.ty + tx
        val newTy = b * other.tx + d * other.ty + ty

        a = newA; b = newB
        c = newC; d = newD
        tx = newTx; ty = newTy
        return this
    }

    /**
     * Sets this matrix to map [src] onto [dst], scaling each axis independently (FILL).
     */
    fun setRectToRect(src: Rect, dst: Rect): Matrix2D {
        val sx = if (src.width != 0f) dst.width / src.width else 1f
        val sy = if (src.height != 0f) dst.height / src.height else 1f
        a = sx; b = 0f
        c = 0f; d = sy
        tx = dst.left - src.left * sx
        ty = dst.top - src.top * sy
        return this
    }

    /** Uniform scale factor of this transformation (length of the transformed X basis vector). */
    val scaleFactor: Float
        get() = hypot(a, b)

    /** Rotation of this transformation in degrees. */
    val rotationDegrees: Float
        get() = (atan2(b, a) * 180.0 / PI).toFloat()

    /** Whether this matrix is the identity transformation. */
    val isIdentity: Boolean
        get() = a == 1f && b == 0f && c == 0f && d == 1f && tx == 0f && ty == 0f

    /**
     * Post-concatenates this matrix with another matrix: M' = other * this.
     */
    fun postConcat(other: Matrix2D): Matrix2D {
        val newA = other.a * a + other.c * b
        val newB = other.b * a + other.d * b
        val newC = other.a * c + other.c * d
        val newD = other.b * c + other.d * d
        val newTx = other.a * tx + other.c * ty + other.tx
        val newTy = other.b * tx + other.d * ty + other.ty

        a = newA; b = newB
        c = newC; d = newD
        tx = newTx; ty = newTy
        return this
    }

    /**
     * Maps a 2D point (x, y) through this transformation matrix.
     */
    fun mapPoint(x: Float, y: Float): Offset {
        return Offset(
            x = a * x + c * y + tx,
            y = b * x + d * y + ty
        )
    }

    /**
     * Maps an array of points in-place or from [src] to [dst].
     * Coordinates are interleaved: [x0, y0, x1, y1, ...]
     */
    fun mapPoints(dst: FloatArray, src: FloatArray = dst) {
        val count = min(dst.size, src.size) / 2
        for (i in 0 until count) {
            val sx = src[i * 2]
            val sy = src[i * 2 + 1]
            dst[i * 2] = a * sx + c * sy + tx
            dst[i * 2 + 1] = b * sx + d * sy + ty
        }
    }

    /**
     * Maps a bounding [Rect] through this transformation matrix, returning the new axis-aligned bounding box.
     */
    fun mapRect(rect: Rect): Rect {
        val p1 = mapPoint(rect.left, rect.top)
        val p2 = mapPoint(rect.right, rect.top)
        val p3 = mapPoint(rect.right, rect.bottom)
        val p4 = mapPoint(rect.left, rect.bottom)

        val minX = min(min(p1.x, p2.x), min(p3.x, p4.x))
        val maxX = max(max(p1.x, p2.x), max(p3.x, p4.x))
        val minY = min(min(p1.y, p2.y), min(p3.y, p4.y))
        val maxY = max(max(p1.y, p2.y), max(p3.y, p4.y))

        return Rect(minX, minY, maxX, maxY)
    }

    /**
     * Calculates the mapped geometric radius under this transformation.
     */
    fun mapRadius(radius: Float): Float {
        // Average length of basis vectors transformed
        val l1 = hypot(a, b)
        val l2 = hypot(c, d)
        return radius * ((l1 + l2) / 2f)
    }

    /**
     * Inverts this matrix and writes the result into [out].
     * Returns `true` if invertible, `false` otherwise.
     */
    fun invert(out: Matrix2D = Matrix2D()): Boolean {
        val det = a * d - b * c
        if (abs(det) < 1e-7f) return false

        val invDet = 1f / det
        val newA = d * invDet
        val newB = -b * invDet
        val newC = -c * invDet
        val newD = a * invDet
        val newTx = (c * ty - d * tx) * invDet
        val newTy = (b * tx - a * ty) * invDet

        out.a = newA; out.b = newB
        out.c = newC; out.d = newD
        out.tx = newTx; out.ty = newTy
        return true
    }

    /**
     * Converts this 2D Matrix to Compose's 4x4 [androidx.compose.ui.graphics.Matrix].
     */
    fun toComposeMatrix(out: androidx.compose.ui.graphics.Matrix = androidx.compose.ui.graphics.Matrix()): androidx.compose.ui.graphics.Matrix {
        out.reset()
        // Compose Matrix is column-major float array of 16 values
        val values = out.values
        values[0] = a
        values[1] = b
        values[4] = c
        values[5] = d
        values[12] = tx
        values[13] = ty
        return out
    }

    override fun toString(): String {
        return "Matrix2D([$a, $c, $tx], [$b, $d, $ty])"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Matrix2D) return false
        return a == other.a && b == other.b && c == other.c && d == other.d && tx == other.tx && ty == other.ty
    }

    override fun hashCode(): Int {
        var result = a.hashCode()
        result = 31 * result + b.hashCode()
        result = 31 * result + c.hashCode()
        result = 31 * result + d.hashCode()
        result = 31 * result + tx.hashCode()
        result = 31 * result + ty.hashCode()
        return result
    }
}
