package io.github.valerioisufi.drawview.canvas.render.tile

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.concurrent.Volatile

/**
 * State representing the lifecycle of a tile.
 */
enum class TileState {
    READY,
    RENDERING,
    STALE
}

/**
 * Represents a single rasterized tile in memory.
 *
 * @property key The unique [TileKey] of this tile in the pyramid.
 * @property bitmap The underlying rasterized Compose ImageBitmap.
 * @property state Current lifecycle state of the tile.
 */
class Tile(
    val key: TileKey,
    val bitmap: ImageBitmap,
    @Volatile var state: TileState = TileState.RENDERING
) {
    /** Last frame of the [TileCache] in which the tile was used (guarded by the cache lock). */
    internal var lastUsedFrame: Long = -1L

    val sizeInBytes: Int
        get() = bitmap.width * bitmap.height * 4
}
