package io.github.valerioisufi.drawview.canvas.render.tile

import androidx.compose.ui.graphics.ImageBitmap
import io.github.valerioisufi.drawview.platform.SyncLock
import io.github.valerioisufi.drawview.platform.syncLock

/**
 * Thread-safe LRU cache for multi-resolution rasterized tiles.
 *
 * The tiles used in the current or in the previous frame (see [beginFrame]) are never evicted, even
 * beyond the budget: a viewport needing more tiles than the budget (a large screen zoomed just past
 * a change of LOD) would otherwise evict its own tiles and rasterize them again at every frame.
 *
 * The overviews of the pages ([TilePyramid.OVERVIEW_LOD]) have a budget of their own: however many
 * tiles a zoomed viewport keeps in use, they stay, to be drawn under the cells of the next zoom out.
 *
 * @param maxMemoryBytes Maximum cache budget in bytes (default: 64 MB) for the tiles not in use,
 * overviews excluded.
 */
public class TileCache(
    private val maxMemoryBytes: Int = 64 * 1024 * 1024
) {
    private val lock = SyncLock()
    private val map = LinkedHashMap<TileKey, Tile>(32, 0.75f)
    private var currentMemoryBytes: Int = 0
    private var overviewMemoryBytes: Int = 0
    private var frame = 0L

    /**
     * Starts a new frame: the tiles used from now on are kept until the end of the next frame.
     */
    internal fun beginFrame() {
        syncLock(lock) { frame++ }
    }

    public fun get(key: TileKey): Tile? {
        return syncLock(lock) {
            val tile = map[key] ?: return@syncLock null
            // Move to end (most recently used)
            map.remove(key)
            map[key] = tile
            tile.lastUsedFrame = frame
            tile
        }
    }

    public fun contains(key: TileKey): Boolean {
        return syncLock(lock) {
            map.containsKey(key)
        }
    }

    internal val entryCount: Int
        get() = syncLock(lock) { map.size }

    public fun put(tile: Tile) {
        syncLock(lock) {
            val old = map.remove(tile.key)
            if (old != null) {
                release(old)
            }
            map[tile.key] = tile
            if (tile.key.lod == TilePyramid.OVERVIEW_LOD) {
                overviewMemoryBytes += tile.sizeInBytes
            } else {
                currentMemoryBytes += tile.sizeInBytes
            }
            // Rasterized because a recent frame needed it
            tile.lastUsedFrame = frame

            trimToSize()
        }
    }

    /** Takes [tile], removed from the map, out of the count of its budget. */
    private fun release(tile: Tile) {
        if (tile.key.lod == TilePyramid.OVERVIEW_LOD) {
            overviewMemoryBytes -= tile.sizeInBytes
        } else {
            currentMemoryBytes -= tile.sizeInBytes
        }
    }

    private fun trimToSize() {
        trimOverviews()
        if (currentMemoryBytes <= maxMemoryBytes) return

        // 1. Evict off-screen high-detail tiles first, from highest LOD down to LOD 1.
        // Detail tiles cover small localized areas and have high churn, whereas base/overview tiles
        // (LOD <= 0) cover full pages and should be preserved to avoid re-rasterization on zoom-out.
        for (targetLod in TilePyramid.MAX_LOD downTo 1) {
            if (currentMemoryBytes <= maxMemoryBytes) return
            val iterator = map.entries.iterator()
            while (iterator.hasNext() && currentMemoryBytes > maxMemoryBytes) {
                val entry = iterator.next()
                if (frame > 0L && entry.value.lastUsedFrame >= frame - 1) continue
                if (entry.key.lod == targetLod) {
                    release(entry.value)
                    iterator.remove()
                }
            }
        }

        // 2. If still over budget, evict off-screen base/overview tiles (LOD <= 0) in LRU order
        val iterator = map.entries.iterator()
        while (iterator.hasNext() && currentMemoryBytes > maxMemoryBytes) {
            val entry = iterator.next()
            if (frame > 0L && entry.value.lastUsedFrame >= frame - 1) continue
            if (entry.key.lod == TilePyramid.OVERVIEW_LOD) continue
            release(entry.value)
            iterator.remove()
        }
    }

    /** Evicts the overviews seen longest ago, the ones in use excepted, down to their budget. */
    private fun trimOverviews() {
        val iterator = map.entries.iterator()
        while (iterator.hasNext() && overviewMemoryBytes > MAX_OVERVIEW_MEMORY_BYTES) {
            val entry = iterator.next()
            if (entry.key.lod != TilePyramid.OVERVIEW_LOD) continue
            if (frame > 0L && entry.value.lastUsedFrame >= frame - 1) continue
            release(entry.value)
            iterator.remove()
        }
    }

    internal fun obtainBitmap(
        width: Int = TilePyramid.TILE_SIZE,
        height: Int = TilePyramid.TILE_SIZE
    ): ImageBitmap {
        return ImageBitmap(width, height)
    }

    /**
     * Finds nearest ancestor tile (lower LOD) in pyramid hierarchy, ready or stale: a stale one is
     * better than a blank cell.
     * Used for smooth hierarchical fallback (mipmap scaling) when target tile is rendering.
     */
    internal fun findNearestAncestor(key: TileKey): Tile? {
        var currentKey = key.getParentKey()
        while (currentKey.lod >= TilePyramid.MIN_LOD) {
            val candidate = get(currentKey)
            if (candidate != null && (candidate.state == TileState.READY || candidate.state == TileState.STALE)) {
                return candidate
            }
            currentKey = currentKey.getParentKey()
        }
        return null
    }

    /**
     * Finds ready descendant tiles (higher LOD) in pyramid hierarchy.
     */
    internal fun findReadyDescendants(key: TileKey, maxGenerations: Int = 2): List<Tile> {
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
    public fun invalidate(predicate: (TileKey) -> Boolean) {
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
    internal fun removeForPage(pageKey: Long) {
        syncLock(lock) {
            val iterator = map.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key.pageKey == pageKey) {
                    release(entry.value)
                    iterator.remove()
                }
            }
        }
    }

    /**
     * Marks all tiles of a specific page matching [predicate] as [TileState.STALE].
     */
    internal fun invalidatePage(pageKey: Long, predicate: (TileKey) -> Boolean = { true }) {
        syncLock(lock) {
            for ((key, tile) in map) {
                if (key.pageKey == pageKey && predicate(key)) {
                    tile.state = TileState.STALE
                }
            }
        }
    }

    /**
     * Evicts every tile whose page is not in [livePageKeys] (deleted pages).
     */
    internal fun retainPages(livePageKeys: Set<Long>) {
        syncLock(lock) {
            val iterator = map.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key.pageKey !in livePageKeys) {
                    release(entry.value)
                    iterator.remove()
                }
            }
        }
    }

    public fun clear() {
        syncLock(lock) {
            map.clear()
            currentMemoryBytes = 0
            overviewMemoryBytes = 0
        }
    }

    private companion object {
        /** The overviews of the last 16 pages seen, at a tile a page. */
        const val MAX_OVERVIEW_MEMORY_BYTES = 16 * 1024 * 1024
    }
}
