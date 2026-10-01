package io.github.valerioisufi.drawview.core.render.tile

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
    val sizeInBytes: Int
        get() = bitmap.width * bitmap.height * 4
}
