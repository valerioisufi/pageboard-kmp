package io.github.valerioisufi.drawview.model

import io.github.valerioisufi.drawview.model.change.DocumentSession
import io.github.valerioisufi.drawview.model.change.RemovePage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A page removed stays in the trash of its document until it is old enough (see `docs/trash.md`). */
class PageTrashPurgeTest {

    @Test
    fun aPageRemovedIsDroppedForGoodOnlyOnceOutOfTheTrash() {
        val document = DrawDocument()
        document.addPage()
        val session = DocumentSession(document, replica = "ada")
        val page = document.pages[1]
        session.submit(RemovePage(page.id))
        val removed = assertNotNull(page.removed)
        assertEquals(listOf(page), document.removedPagesSince(removed.time))
        assertTrue(document.removedPagesSince(removed.time + 1).isEmpty())

        assertTrue(document.purgeHidden(pagesRemovedBefore = removed.time) { true }.pages.isEmpty(), "removed at the time given: kept")
        assertEquals(listOf(page), document.purgeHidden(pagesRemovedBefore = removed.time + 1) { true }.pages)
        assertTrue(document.removedPages.isEmpty())
    }
}
