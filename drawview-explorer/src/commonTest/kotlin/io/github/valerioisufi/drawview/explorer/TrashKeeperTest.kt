package io.github.valerioisufi.drawview.explorer

import io.github.valerioisufi.drawview.model.TrashRetention
import io.github.valerioisufi.drawview.store.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.store.KeyValueDocumentStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class TrashKeeperTest {

    @Test
    fun whatHasBeenInTheTrashLongerThanItKeepsIsDeletedForGood() = runTest {
        var now = 1_000L
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = backgroundScope, clock = { now })
        val old = store.createDocument("Old")
        assertTrue(store.trashDocument(old.id))
        now += TrashRetention.MILLIS - 1
        val recent = store.createDocument("Recent")
        assertTrue(store.trashDocument(recent.id))

        val keeper = TrashKeeper(store, clock = { now })
        keeper.purgeNow()
        assertEquals(2, store.trash.first().size, "not older than 30 days yet")

        // A day later, while the library runs
        val running = launch { keeper.run() }
        runCurrent()
        now += TrashKeeper.INTERVAL_MILLIS
        advanceTimeBy(TrashKeeper.INTERVAL_MILLIS + 1)
        assertEquals(listOf(recent.id), store.trash.first().map { it.id })
        assertNull(store.loadDocument(old.id))
        running.cancel()
    }
}
