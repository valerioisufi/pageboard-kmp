package io.github.valerioisufi.pageboard.model.change

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The operations of a [PersistenceQueue] run in order, a failed one tried again before the next. */
class PersistenceQueueTest {

    @Test
    fun anOperationThatFailsIsTriedAgainBeforeTheNextOne() = runTest {
        val queue = PersistenceQueue(backgroundScope, NoOpDocumentPersistence)
        val done = mutableListOf<String>()
        var tries = 0
        queue.enqueue {
            tries++
            // A database busy twice, then free
            if (tries < 3) error("busy")
            done += "stroke"
        }
        queue.enqueue { done += "text" }
        queue.flush()
        assertEquals(3, tries)
        assertEquals(listOf("stroke", "text"), done)
    }

    @Test
    fun anOperationThatNeverSucceedsDoesNotStopTheOthers() = runTest {
        val queue = PersistenceQueue(backgroundScope, NoOpDocumentPersistence)
        val done = mutableListOf<String>()
        var tries = 0
        queue.enqueue {
            tries++
            error("broken")
        }
        queue.enqueue { done += "text" }
        queue.flush()
        // The first try and one after each pause
        assertEquals(4, tries)
        assertEquals(listOf("text"), done)
    }
}
