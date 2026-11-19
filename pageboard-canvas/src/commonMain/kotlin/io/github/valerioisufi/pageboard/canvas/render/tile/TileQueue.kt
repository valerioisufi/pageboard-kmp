package io.github.valerioisufi.pageboard.canvas.render.tile

import io.github.valerioisufi.pageboard.platform.SyncLock
import io.github.valerioisufi.pageboard.platform.syncLock

/**
 * How much a tile is needed, the most needed first.
 */
internal enum class TilePriority {
    /** The missing overview of a page on screen: one tile covers the whole page. */
    PageOverview,

    /** A cell on screen. */
    OnScreen,

    /** The margin around the screen, and the overviews of the pages near it. */
    Margin,

    /** A stale tile drawn in place of a missing one, whose own tile is coming anyway. */
    Refresh
}

/**
 * What the workers rasterize next. Thread-safe.
 *
 * Every frame asks for the tiles it wants ([beginFrame], [ask], [endFrame]): what the last frame did
 * not ask for leaves the queue, so that after a fling or a zoom across the levels the workers do not
 * rasterize tiles no longer shown. A tile being rasterized is left to finish.
 *
 * @param maxWorkers How many workers may rasterize at the same time.
 */
internal class TileQueue(private val maxWorkers: Int) {
    /** A tile waiting for a worker: [work] rasterizes it. */
    internal class Request(
        val key: TileKey,
        val priority: TilePriority,
        val order: Float,
        val frame: Long,
        val work: () -> Unit
    )

    private val lock = SyncLock()
    private val pending = HashMap<TileKey, Request>()
    private val running = HashSet<TileKey>()
    private var workers = 0
    private var frame = 0L

    /** Whether tiles are waiting or being rasterized. */
    val isBusy: Boolean
        get() = syncLock(lock) { pending.isNotEmpty() || running.isNotEmpty() }

    fun beginFrame() {
        syncLock(lock) { frame++ }
    }

    /**
     * Asks for the tile [key], unless it is being rasterized. Asked twice in a frame, it keeps the
     * higher of its priorities.
     *
     * @param order Which tile comes first within a priority, the lowest first.
     */
    fun ask(key: TileKey, priority: TilePriority, order: Float = 0f, work: () -> Unit) {
        syncLock(lock) {
            if (key in running) return
            val asked = pending[key]
            if (asked != null && asked.frame == frame && asked.priority <= priority) return
            pending[key] = Request(key, priority, order, frame, work)
        }
    }

    /** Ends the frame: the tiles it did not ask for leave the queue. */
    fun endFrame() {
        syncLock(lock) { pending.values.removeAll { it.frame != frame } }
    }

    /** Counts a new worker, when the tiles waiting need one: it takes them with [next]. */
    fun addWorker(): Boolean = syncLock(lock) {
        val idle = workers - running.size
        if (workers < maxWorkers && idle < pending.size) {
            workers++
            true
        } else {
            false
        }
    }

    /**
     * The most needed tile, now being rasterized until [finished]; null when none waits, and the
     * worker asking is no longer counted (a tile asked for from now on gets a new worker).
     */
    fun next(): Request? = syncLock(lock) {
        val request = pending.values.minWithOrNull(compareBy({ it.priority }, { it.order }))
        if (request == null) {
            workers--
        } else {
            pending.remove(request.key)
            running.add(request.key)
        }
        request
    }

    fun finished(key: TileKey) {
        syncLock(lock) { running.remove(key) }
    }

    /** A worker stopped by a failure is no longer counted. */
    fun retireWorker() {
        syncLock(lock) { workers-- }
    }

    /** The tiles waiting of the page [pageKey] leave the queue. */
    fun removePage(pageKey: Long) {
        syncLock(lock) { pending.values.removeAll { it.key.pageKey == pageKey } }
    }

    /** All the tiles waiting leave the queue. */
    fun clear() {
        syncLock(lock) { pending.clear() }
    }
}
