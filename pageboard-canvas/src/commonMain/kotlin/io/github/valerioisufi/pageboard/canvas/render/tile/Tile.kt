package io.github.valerioisufi.pageboard.canvas.render.tile

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.concurrent.Volatile

/**
 * State representing the lifecycle of a tile.
 */
public enum class TileState {
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
public class Tile(
    public val key: TileKey,
    public val bitmap: ImageBitmap,
    @Volatile public var state: TileState = TileState.RENDERING
) {
    /** Last frame of the [TileCache] in which the tile was used (guarded by the cache lock). */
    internal var lastUsedFrame: Long = -1L

    internal val sizeInBytes: Int
        get() = bitmap.width * bitmap.height * 4
}
