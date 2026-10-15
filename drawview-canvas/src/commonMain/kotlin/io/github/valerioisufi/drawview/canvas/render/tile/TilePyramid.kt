package io.github.valerioisufi.drawview.canvas.render.tile

import androidx.compose.ui.geometry.Rect
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

/**
 * Mathematical utilities for Level of Detail (LOD) calculations, coordinate system conversions,
 * and tile pyramid spatial queries.
 */
internal object TilePyramid {
    /** Fixed tile dimension in pixels (width and height). */
    const val TILE_SIZE = 512

    /** Minimum supported LOD index (zoomed out). Scale = 2^-3 = 0.125x. */
    const val MIN_LOD = -3

    /** Maximum supported LOD index (deep zoom). Scale = 2^5 = 32x. */
    const val MAX_LOD = 5

    /** Largest magnification of the tiles of the nearest LOD (halfway between two LODs). */
    const val NEAREST_LOD_MAGNIFICATION = 1.4142135f

    /**
     * Largest magnification of the tiles on a screen of [density] (pixels per dp). The pixels of a
     * screen of 2x and more are too small to see a tile magnified up to √2, so the nearest LOD is
     * drawn; on a screen of 1x to 1.75x a magnified tile is blurred, so the tiles are only shrunk
     * there (the 5% leaves room for the rounding of the zoom), at the cost of up to twice the
     * tiles of the nearest LOD.
     */
    fun maxMagnification(density: Float): Float = if (density >= 2f) NEAREST_LOD_MAGNIFICATION else 1.05f

    /**
     * Calculates the LOD for the continuous camera [scale]: the lowest whose tiles are magnified
     * on screen at most by [maxMagnification] (by default the nearest LOD).
     */
    fun calculateLod(scale: Float, maxMagnification: Float = NEAREST_LOD_MAGNIFICATION): Int {
        if (scale <= 0f) return 0
        val rawLod = ceil(ln(scale.toDouble() / maxMagnification) / ln(2.0)).toInt()
        return rawLod.coerceIn(MIN_LOD, MAX_LOD)
    }

    /**
     * Returns the exact scale factor corresponding to an integer LOD level.
     */
    fun getScaleForLod(lod: Int): Float {
        return 2.0f.pow(lod.toFloat())
    }

    /**
     * Returns the width and height of a tile in World Space units at a given LOD.
     */
    fun getTileWorldSize(lod: Int): Float {
        val scale = getScaleForLod(lod)
        return TILE_SIZE / scale
    }

    /**
     * Returns the bounding box in Page-Local Space covered by the given tile.
     */
    fun getTilePageRect(key: TileKey): Rect {
        val size = getTileWorldSize(key.lod)
        val left = key.col * size
        val top = key.row * size
        return Rect(left, top, left + size, top + size)
    }

    /**
     * Discrete rectangular range of tile indices in the grid.
     */
    data class TileRange(
        val minCol: Int,
        val maxCol: Int,
        val minRow: Int,
        val maxRow: Int
    ) {
        val count: Int
            get() = (maxCol - minCol + 1) * (maxRow - minRow + 1)

        inline fun forEach(action: (col: Int, row: Int) -> Unit) {
            for (r in minRow..maxRow) {
                for (c in minCol..maxCol) {
                    action(c, r)
                }
            }
        }
    }

    /**
     * Computes the bounding tile range [minCol..maxCol, minRow..maxRow] intersecting a region of a page
     * in page-local unscaled coordinates, bounded by the page's dimensions.
     */
    fun getPageIntersectingTileRange(
        localLeft: Float,
        localTop: Float,
        localRight: Float,
        localBottom: Float,
        pageWidth: Float,
        pageHeight: Float,
        lod: Int,
        overscanMargin: Int = 0
    ): TileRange {
        val size = getTileWorldSize(lod)
        val maxCol = floor(pageWidth / size).toInt().coerceAtLeast(0)
        val maxRow = floor(pageHeight / size).toInt().coerceAtLeast(0)
        val minCol = (floor(localLeft / size).toInt() - overscanMargin).coerceIn(0, maxCol)
        val endCol = (floor(localRight / size).toInt() + overscanMargin).coerceIn(minCol, maxCol)
        val minRow = (floor(localTop / size).toInt() - overscanMargin).coerceIn(0, maxRow)
        val endRow = (floor(localBottom / size).toInt() + overscanMargin).coerceIn(minRow, maxRow)
        return TileRange(minCol, endCol, minRow, endRow)
    }

    /**
     * Transformation matrix mapping a tile's pixels [0..TILE_SIZE, 0..TILE_SIZE]
     * directly into Screen Canvas coordinates under the current camera transformation.
     */
    fun getTileToScreenMatrix(
        key: TileKey,
        pageWorldRect: Rect,
        cameraMatrix: Matrix2D,
        outMatrix: Matrix2D = Matrix2D()
    ): Matrix2D {
        val lodScale = getScaleForLod(key.lod)
        val size = TILE_SIZE / lodScale
        val localLeft = key.col * size
        val localTop = key.row * size

        outMatrix.reset()
        outMatrix.postScale(1f / lodScale, 1f / lodScale)
        outMatrix.postTranslate(localLeft + pageWorldRect.left, localTop + pageWorldRect.top)
        outMatrix.postConcat(cameraMatrix)
        return outMatrix
    }

    /**
     * Calculates the transformation matrix mapping page millimeter coordinates
     * directly into the tile bitmap pixels [0..TILE_SIZE, 0..TILE_SIZE].
     */
    fun getMmToTileMatrix(
        key: TileKey,
        pageRectMm: Rect,
        pageWorldRect: Rect,
        outMatrix: Matrix2D = Matrix2D()
    ): Matrix2D {
        val lodScale = getScaleForLod(key.lod)
        val size = TILE_SIZE / lodScale
        val localLeft = key.col * size
        val localTop = key.row * size

        outMatrix.reset()
        val scaleX = if (pageRectMm.width > 0f) pageWorldRect.width / pageRectMm.width else 1f
        val scaleY = if (pageRectMm.height > 0f) pageWorldRect.height / pageRectMm.height else 1f
        outMatrix.postScale(scaleX, scaleY)
        outMatrix.postTranslate(-localLeft, -localTop)
        outMatrix.postScale(lodScale, lodScale)
        return outMatrix
    }

    /**
     * Calculates the transformation matrix mapping World Space coordinates
     * into the local pixel coordinates [0..TILE_SIZE, 0..TILE_SIZE] of the tile bitmap.
     */
    fun getWorldToTileMatrix(
        key: TileKey,
        pageWorldRect: Rect,
        outMatrix: Matrix2D = Matrix2D()
    ): Matrix2D {
        val lodScale = getScaleForLod(key.lod)
        val size = TILE_SIZE / lodScale
        val worldLeft = pageWorldRect.left + key.col * size
        val worldTop = pageWorldRect.top + key.row * size

        outMatrix.reset()
        outMatrix.postTranslate(-worldLeft, -worldTop)
        outMatrix.postScale(lodScale, lodScale)
        return outMatrix
    }
}
