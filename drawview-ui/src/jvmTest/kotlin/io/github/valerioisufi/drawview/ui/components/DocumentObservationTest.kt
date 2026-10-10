package io.github.valerioisufi.drawview.ui.components

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.change.RenameDocument
import io.github.valerioisufi.drawview.ui.theme.DrawViewTheme
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * The model is not Compose state: what shows the document follows its revision (see
 * `observeChanges`), so that it shows the changes as they are made.
 */
@OptIn(ExperimentalTestApi::class)
class DocumentObservationTest {

    @Test
    fun thePageGridShowsThePagesAndTheTitleAsTheyChange() = runDesktopComposeUiTest {
        val controller = DrawViewController(document = DrawDocument(title = "Notes"), coroutineScope = CoroutineScope(Dispatchers.Unconfined))
        setContent {
            DrawViewTheme(dynamicColor = false) {
                PageGridOverlay(controller)
            }
        }
        onNodeWithText("Notes · 1 page").assertExists()

        runOnIdle { controller.pageManager.addNewPageAtBottom() }
        onNodeWithText("Notes · 2 pages").assertExists()

        runOnIdle { controller.session.submit(RenameDocument("Analysis")) }
        onNodeWithText("Analysis · 2 pages").assertExists()
    }
}
