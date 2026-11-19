package io.github.valerioisufi.pageboard.canvas

import io.github.valerioisufi.pageboard.canvas.render.tile.TileKey
import io.github.valerioisufi.pageboard.canvas.render.tile.TilePriority
import io.github.valerioisufi.pageboard.canvas.render.tile.TileQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TileQueueTest {
    private fun key(lod: Int, col: Int = 0, page: Long = 0L) = TileKey(page, lod, col, 0)

    private fun TileQueue.frame(asks: TileQueue.() -> Unit) {
        beginFrame()
        asks()
        endFrame()
    }

    private fun TileQueue.ask(key: TileKey, priority: TilePriority, order: Float = 0f) = ask(key, priority, order) {}

    /** The tiles in the order the workers take them. */
    private fun TileQueue.drain(): List<TileKey> {
        assertTrue(addWorker())
        return generateSequence { next()?.key?.also(::finished) }.toList()
    }

    @Test
    fun theMostNeededTilesComeFirstTheCenterFirstAmongThem() {
        val queue = TileQueue(maxWorkers = 2)
        queue.frame {
            ask(key(3, col = 1), TilePriority.Refresh)
            ask(key(1, col = 1), TilePriority.Margin)
            ask(key(0, col = 2), TilePriority.OnScreen, order = 9f)
            ask(key(0, col = 1), TilePriority.OnScreen, order = 1f)
            ask(key(-2), TilePriority.PageOverview)
        }
        assertEquals(
            listOf(key(-2), key(0, col = 1), key(0, col = 2), key(1, col = 1), key(3, col = 1)),
            queue.drain()
        )
    }

    @Test
    fun whatTheLastFrameDidNotAskForLeavesTheQueue() {
        val queue = TileQueue(maxWorkers = 2)
        queue.frame { for (col in 0 until 40) ask(key(3, col), TilePriority.OnScreen) }
        queue.frame { ask(key(0), TilePriority.OnScreen) }
        assertEquals(listOf(key(0)), queue.drain())
        assertFalse(queue.isBusy)
    }

    @Test
    fun aTileAskedTwiceInAFrameKeepsItsHigherPriority() {
        val queue = TileQueue(maxWorkers = 2)
        queue.frame {
            ask(key(0, col = 1), TilePriority.OnScreen, order = 5f)
            ask(key(0, col = 2), TilePriority.OnScreen, order = 7f)
            // The margin asks again for the tiles on screen
            ask(key(0, col = 1), TilePriority.Margin)
        }
        assertEquals(listOf(key(0, col = 1), key(0, col = 2)), queue.drain())
    }

    @Test
    fun aTileBeingRasterizedFinishesAndIsNotAskedAgain() {
        val queue = TileQueue(maxWorkers = 2)
        queue.frame { ask(key(0), TilePriority.OnScreen) }
        assertTrue(queue.addWorker())
        val request = queue.next()
        assertEquals(key(0), request?.key)

        queue.frame {
            ask(key(0), TilePriority.OnScreen)
            ask(key(1), TilePriority.OnScreen)
        }
        assertTrue(queue.isBusy)
        assertEquals(key(1), queue.next()?.key)
        queue.finished(key(1))
        assertNull(queue.next())
        assertTrue(queue.isBusy)
        queue.finished(key(0))
        assertFalse(queue.isBusy)
    }

    @Test
    fun workersStartOnlyAsNeededUpToTheLimit() {
        val queue = TileQueue(maxWorkers = 2)
        assertFalse(queue.addWorker())
        queue.frame { ask(key(0), TilePriority.OnScreen) }
        assertTrue(queue.addWorker())
        // One tile, one worker idle for it
        assertFalse(queue.addWorker())

        queue.frame { for (col in 0 until 10) ask(key(0, col), TilePriority.OnScreen) }
        assertTrue(queue.addWorker())
        assertFalse(queue.addWorker())

        // Every worker gone, a tile asked for gets a new one
        val keys = generateSequence { queue.next()?.key?.also(queue::finished) }.count()
        assertEquals(10, keys)
        assertNull(queue.next())
        queue.frame { ask(key(1), TilePriority.OnScreen) }
        assertTrue(queue.addWorker())
    }

    @Test
    fun theTilesOfAPageLeaveTheQueueWithIt() {
        val queue = TileQueue(maxWorkers = 2)
        queue.frame {
            ask(key(0, page = 1L), TilePriority.OnScreen)
            ask(key(0, page = 2L), TilePriority.OnScreen)
        }
        queue.removePage(1L)
        assertEquals(listOf(key(0, page = 2L)), queue.drain())
    }
}
