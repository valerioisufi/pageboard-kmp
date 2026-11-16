package io.github.valerioisufi.drawview.ui.components

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.change.DocumentChange
import io.github.valerioisufi.drawview.model.change.Operation
import io.github.valerioisufi.drawview.model.change.OperationId
import io.github.valerioisufi.drawview.model.change.RemovePage
import io.github.valerioisufi.drawview.ui.theme.DrawViewTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * The page grid shows the pages as they are after every change, made here or by another device
 * (see `docs/plans/page-grid-updates.md`): the model is not Compose state.
 */
@OptIn(ExperimentalTestApi::class)
class PageGridUpdatesTest {

    /** A document of four pages, shown in the grid. */
    private fun ComposeUiTest.grid(): DrawViewController {
        val document = DrawDocument(title = "Notes").apply { repeat(3) { addPage() } }
        val controller = DrawViewController(document = document, coroutineScope = CoroutineScope(Dispatchers.Unconfined))
        controller.isPageGridVisible = true
        setContent {
            DrawViewTheme(dynamicColor = false) {
                PageGridOverlay(controller)
            }
        }
        return controller
    }

    /** The numbers of the cards of the pages shown. */
    private fun ComposeUiTest.cards(): List<Int> =
        (1..10).filter { onAllNodesWithContentDescription("Page $it").fetchSemanticsNodes().isNotEmpty() }

    /** The operation of another device making [change] on the document of [controller]. */
    private fun otherDevice(controller: DrawViewController, change: DocumentChange) =
        Operation(OperationId("other", 1), controller.session.clock.next().copy(replica = "other"), change)

    @Test
    fun pagesDeletedLeaveTheGridForTheTrashAndComeBackUndone() = runDesktopComposeUiTest {
        val controller = grid()
        val ids = controller.document.pages.map { it.id }
        assertEquals(listOf(1, 2, 3, 4), cards())

        runOnIdle { controller.pageManager.deletePages(listOf(ids[1], ids[2])) }
        assertEquals(listOf(1, 2), cards())
        onNodeWithText("Deleted pages (2)").assertExists()

        runOnIdle { controller.undo() }
        assertEquals(listOf(1, 2, 3, 4), cards())
        onNodeWithText("Deleted pages", substring = true).assertDoesNotExist()
    }

    @Test
    fun pagesAddedAndRestoredShowInTheGrid() = runDesktopComposeUiTest {
        val controller = grid()
        val ids = controller.document.pages.map { it.id }

        runOnIdle { controller.pageManager.addNewPageAfter(0) }
        assertEquals(listOf(1, 2, 3, 4, 5), cards())

        runOnIdle { controller.pageManager.deletePages(listOf(ids[3])) }
        assertEquals(listOf(1, 2, 3, 4), cards())
        onNodeWithText("Deleted pages (1)").assertExists()

        runOnIdle { controller.pageManager.restorePages(listOf(ids[3])) }
        assertEquals(listOf(1, 2, 3, 4, 5), cards())
        onNodeWithText("Deleted pages", substring = true).assertDoesNotExist()
    }

    @Test
    fun aPageRemovedByAnotherDeviceLeavesTheGrid() = runDesktopComposeUiTest {
        val controller = grid()
        val removed = controller.document.pages[2].id

        runOnIdle { controller.session.receive(otherDevice(controller, RemovePage(removed))) }
        assertEquals(listOf(1, 2, 3), cards())
        onNodeWithText("Notes · 3 pages").assertExists()
    }

    @Test
    fun theBarOfTheSelectionCountsThePagesStillShown() = runDesktopComposeUiTest {
        val controller = grid()
        val ids = controller.document.pages.map { it.id }
        val selection = controller.pageManager.selection
        runOnIdle {
            selection.toggle(ids[1])
            selection.toggle(ids[3])
        }
        onNodeWithText("2 pages selected").assertExists()

        // Still some pages to delete, the bar is the same but for the number
        runOnIdle { controller.session.receive(otherDevice(controller, RemovePage(ids[3]))) }
        onNodeWithText("1 page selected").assertExists()
    }
}
