package io.github.valerioisufi.drawview.canvas.geometry

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.Matrix
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import io.github.valerioisufi.drawview.model.geometry.Point
import io.github.valerioisufi.drawview.model.geometry.Rect

/*
 * The border between the geometry of the document model (headless) and the one of Compose: the
 * canvas draws and receives its pointers in Compose types, the model speaks its own.
 */

fun Rect.toCompose(): ComposeRect = ComposeRect(left, top, right, bottom)

fun ComposeRect.toModel(): Rect = Rect(left, top, right, bottom)

fun Point.toCompose(): Offset = Offset(x, y)

fun Offset.toModel(): Point = Point(x, y)

/** [rect] mapped through this transformation: the box holding the mapped corners. */
fun Matrix2D.mapRect(rect: ComposeRect): ComposeRect = mapRect(rect.toModel()).toCompose()

/** [offset] mapped through this transformation. */
fun Matrix2D.mapOffset(offset: Offset): Offset = mapPoint(offset.x, offset.y).toCompose()

/** Sets this transformation to the one fitting [src] into [dst]. */
fun Matrix2D.setRectToRect(src: ComposeRect, dst: ComposeRect): Matrix2D = setRectToRect(src.toModel(), dst.toModel())

/** This transformation as the 4x4 matrix of Compose (written into [out]). */
fun Matrix2D.toComposeMatrix(out: Matrix = Matrix()): Matrix {
    out.reset()
    // Column-major 4x4
    val values = out.values
    values[0] = a
    values[1] = b
    values[4] = c
    values[5] = d
    values[12] = tx
    values[13] = ty
    return out
}
