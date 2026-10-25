package io.github.valerioisufi.drawview.ui.components

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.ui.theme.DrawViewTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** Selecting pages in the grid with touch and acting on all of them (see `docs/page-selection.md`). */
@OptIn(ExperimentalTestApi::class)
class PageGridSelectionTest {

    @Test
    fun pagesSelectedWithTouchAreDeletedTogether() = runDesktopComposeUiTest {
        val document = DrawDocument(title = "Notes").apply { repeat(4) { addPage() } }
        val controller = DrawViewController(document = document, coroutineScope = CoroutineScope(Dispatchers.Unconfined))
        controller.isPageGridVisible = true
        val pages = document.pages.map { it.id }
        val (second, fourth) = pages[1] to pages[3]
        setContent {
            DrawViewTheme(dynamicColor = false) {
                PageGridOverlay(controller)
            }
        }
        onNodeWithContentDescription("Select").performClick()
        // A tap selects instead of showing the page
        onNodeWithContentDescription("Page 2").performClick()
        onNodeWithContentDescription("Page 4").performClick()
        onNodeWithText("2 pages selected").assertExists()
        assertEquals(listOf(second, fourth), controller.pageManager.selection.pageIds)
        assertEquals(true, controller.isPageGridVisible)

        onNodeWithContentDescription("Delete").assertIsEnabled().performClick()
        assertEquals(pages - second - fourth, document.pages.map { it.id })
        onNodeWithText("0 pages selected").assertExists()

        // Every page selected: not deleted
        onNodeWithContentDescription("Select all").performClick()
        onNodeWithContentDescription("Delete").assertIsNotEnabled()

        onNodeWithContentDescription("Done").performClick()
        assertFalse(controller.pageManager.selection.isActive)
        onNodeWithText("Notes · ${pages.size - 2} pages").assertExists()
    }
}
