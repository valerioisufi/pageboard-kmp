package io.github.valerioisufi.drawview.core.render.tile

import androidx.compose.ui.graphics.ImageBitmap
import io.github.valerioisufi.drawview.core.util.SyncLock
import io.github.valerioisufi.drawview.core.util.syncLock

/**
 * Thread-safe LRU cache for multi-resolution rasterized tiles.
 *
 * The tiles used in the current or in the previous frame (see [beginFrame]) are never evicted, even
 * beyond the budget: a viewport needing more tiles than the budget (a large screen zoomed just past
 * a change of LOD) would otherwise evict its own tiles and rasterize them again at every frame.
 *
 * @param maxMemoryBytes Maximum cache budget in bytes (default: 64 MB) for the tiles not in use.
 */
class TileCache(
    private val maxMemoryBytes: Int = 64 * 1024 * 1024
) {
    private val lock = SyncLock()
    private val map = LinkedHashMap<TileKey, Tile>(32, 0.75f)
    private var currentMemoryBytes: Int = 0
    private var frame = 0L

    /**
     * Starts a new frame: the tiles used from now on are kept until the end of the next frame.
     */
    fun beginFrame() {
        syncLock(lock) { frame++ }
    }

    fun get(key: TileKey): Tile? {
        return syncLock(lock) {
            val tile = map[key] ?: return@syncLock null
            // Move to end (most recently used)
            map.remove(key)
            map[key] = tile
            tile.lastUsedFrame = frame
            tile
        }
    }

    fun contains(key: TileKey): Boolean {
        return syncLock(lock) {
            map.containsKey(key)
        }
    }

    val entryCount: Int
        get() = syncLock(lock) { map.size }

    fun put(tile: Tile) {
        syncLock(lock) {
            val old = map.remove(tile.key)
            if (old != null) {
                currentMemoryBytes -= old.sizeInBytes
            }
            map[tile.key] = tile
            currentMemoryBytes += tile.sizeInBytes
            // Rasterized because a recent frame needed it
            tile.lastUsedFrame = frame

            trimToSize()
        }
    }

    private fun trimToSize() {
        val iterator = map.entries.iterator()
        while (iterator.hasNext() && currentMemoryBytes > maxMemoryBytes) {
            val entry = iterator.next()
            if (frame > 0L && entry.value.lastUsedFrame >= frame - 1) continue
            currentMemoryBytes -= entry.value.sizeInBytes
            iterator.remove()
        }
    }

    fun obtainBitmap(
        width: Int = TilePyramid.TILE_SIZE,
        height: Int = TilePyramid.TILE_SIZE
    ): ImageBitmap {
        return ImageBitmap(width, height)
    }

    /**
     * Finds nearest ready ancestor tile (lower LOD) in pyramid hierarchy.
     * Used for smooth hierarchical fallback (mipmap scaling) when target tile is rendering.
     */
    fun findNearestAncestor(key: TileKey, maxGenerations: Int = 3): Tile? {
        var currentKey = key.getParentKey()
        var generations = 0
        while (generations < maxGenerations && currentKey.lod >= TilePyramid.MIN_LOD) {
            val candidate = get(currentKey)
            if (candidate != null && candidate.state == TileState.READY) {
                return candidate
            }
            currentKey = currentKey.getParentKey()
            generations++
        }
        return null
    }

    /**
     * Finds ready descendant tiles (higher LOD) in pyramid hierarchy.
     */
    fun findReadyDescendants(key: TileKey, maxGenerations: Int = 2): List<Tile> {
        val readyDescendants = mutableListOf<Tile>()
        fun collect(currentKey: TileKey, gen: Int) {
            if (gen > maxGenerations || currentKey.lod >= TilePyramid.MAX_LOD) return
            currentKey.forEachChildKey { childKey ->
                val childTile = get(childKey)
                if (childTile != null && childTile.state == TileState.READY) {
                    readyDescendants.add(childTile)
                } else {
                    collect(childKey, gen + 1)
                }
            }
        }
        collect(key, 1)
        return readyDescendants
    }

    /**
     * Marks all tiles matching [predicate] as [TileState.STALE].
     * Stale tiles remain in cache and continue to be drawn to prevent flashes.
     */
    fun invalidate(predicate: (TileKey) -> Boolean) {
        syncLock(lock) {
            for ((key, tile) in map) {
                if (predicate(key)) {
                    tile.state = TileState.STALE
                }
            }
        }
    }

    /**
     * Evicts all tiles associated with a specific page from cache.
     */
    fun removeForPage(pageUid: Long) {
        syncLock(lock) {
            val iterator = map.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key.pageUid == pageUid) {
                    currentMemoryBytes -= entry.value.sizeInBytes
                    iterator.remove()
                }
            }
        }
    }

    /**
     * Marks all tiles of a specific page matching [predicate] as [TileState.STALE].
     */
    fun invalidatePage(pageUid: Long, predicate: (TileKey) -> Boolean = { true }) {
        syncLock(lock) {
            for ((key, tile) in map) {
                if (key.pageUid == pageUid && predicate(key)) {
                    tile.state = TileState.STALE
                }
            }
        }
    }

    /**
     * Evicts every tile whose page is not in [livePageUids] (deleted pages).
     */
    fun retainPages(livePageUids: Set<Long>) {
        syncLock(lock) {
            val iterator = map.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key.pageUid !in livePageUids) {
                    currentMemoryBytes -= entry.value.sizeInBytes
                    iterator.remove()
                }
            }
        }
    }

    fun clear() {
        syncLock(lock) {
            map.clear()
            currentMemoryBytes = 0
        }
    }
}
