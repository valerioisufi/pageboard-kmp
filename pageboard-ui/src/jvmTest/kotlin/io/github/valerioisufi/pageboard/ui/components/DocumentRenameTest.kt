package io.github.valerioisufi.pageboard.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.github.valerioisufi.pageboard.canvas.controller.DrawViewController
import io.github.valerioisufi.pageboard.model.DrawDocument
import io.github.valerioisufi.pageboard.model.change.RenameDocument
import io.github.valerioisufi.pageboard.ui.screen.DocumentNames
import io.github.valerioisufi.pageboard.ui.screen.DocumentRenamer
import io.github.valerioisufi.pageboard.ui.theme.DrawViewTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * The document renamed from its title in the bar of the editor (see `docs/plans/title-bar.md`): the
 * name written is taken by Enter or by closing the popover, checked as in the library.
 */
@OptIn(ExperimentalTestApi::class)
class DocumentRenameTest {

    /** A renamer of the document of [controller] that refuses the names of [taken], recording the names asked. */
    private class Renamer(private val controller: DrawViewController, private val taken: Set<String> = emptySet()) : DocumentRenamer {
        val asked = mutableListOf<String>()
        override suspend fun rename(name: String): Boolean {
            asked += name
            if (name in taken) return false
            controller.session.submit(RenameDocument(name))
            return true
        }
    }

    private fun controller() = DrawViewController(document = DrawDocument(title = "Notes"), coroutineScope = CoroutineScope(Dispatchers.Unconfined))

    private fun SemanticsNodeInteractionsProvider.field() = onNode(hasSetTextAction())

    @Test
    fun enterTakesTheNameWrittenTrimmed() = runDesktopComposeUiTest {
        val controller = controller()
        val renamer = Renamer(controller)
        setContent { DrawViewTheme(dynamicColor = false) { DocumentInfoSelector(controller.document, renamer = renamer) } }

        onNodeWithText("Notes").performClick()
        field().performTextReplacement("  Physics ")
        field().performImeAction()
        waitForIdle()

        assertEquals(listOf("Physics"), renamer.asked)
        assertEquals("Physics", controller.document.title)
        // The popover is closed, the title of the bar renamed
        assertTrue(onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty())
        onNodeWithText("Physics").assertExists()
    }

    @Test
    fun aNameTakenInTheFolderIsShownAndNotTaken() = runDesktopComposeUiTest {
        val controller = controller()
        val renamer = Renamer(controller, taken = setOf("Physics"))
        setContent { DrawViewTheme(dynamicColor = false) { DocumentInfoSelector(controller.document, renamer = renamer) } }

        onNodeWithText("Notes").performClick()
        field().performTextReplacement("Physics")
        field().performImeAction()
        waitForIdle()

        assertEquals("Notes", controller.document.title)
        onNodeWithText("This name already exists").assertExists()
        field().assertExists()
    }

    @Test
    fun anInvalidNameIsNeverAsked() = runDesktopComposeUiTest {
        val controller = controller()
        val renamer = Renamer(controller)
        setContent { DrawViewTheme(dynamicColor = false) { DocumentInfoSelector(controller.document, renamer = renamer) } }

        onNodeWithText("Notes").performClick()
        field().performTextReplacement("a/b")
        onNodeWithText("Name cannot contain slashes").assertExists()
        field().performImeAction()
        field().performTextReplacement("   ")
        onNodeWithText("Name cannot be empty").assertExists()
        field().performImeAction()
        waitForIdle()

        assertTrue(renamer.asked.isEmpty())
        assertEquals("Notes", controller.document.title)
    }

    @Test
    fun escapeGivesTheOldNameBack() = runDesktopComposeUiTest {
        val controller = controller()
        val renamer = Renamer(controller)
        setContent { DrawViewTheme(dynamicColor = false) { DocumentInfoSelector(controller.document, renamer = renamer) } }

        onNodeWithText("Notes").performClick()
        field().performTextReplacement("Physics")
        field().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()

        assertTrue(renamer.asked.isEmpty())
        assertEquals("Notes", controller.document.title)
        assertTrue(onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun closingThePopoverTakesAValidName() = runDesktopComposeUiTest {
        val controller = controller()
        val renamer = Renamer(controller)
        setContent { DrawViewTheme(dynamicColor = false) { DocumentInfoSelector(controller.document, renamer = renamer) } }

        onNodeWithText("Notes").performClick()
        field().performTextReplacement("Physics")
        // The title tapped again closes the popover (the field holds the new name)
        onNodeWithText("Notes").performClick()
        waitForIdle()

        assertEquals(listOf("Physics"), renamer.asked)
        assertEquals("Physics", controller.document.title)
    }

    @Test
    fun withoutARenamerTheTitleIsOnlyShown() = runDesktopComposeUiTest {
        val controller = controller()
        setContent { DrawViewTheme(dynamicColor = false) { DocumentInfoSelector(controller.document) } }

        onNodeWithText("Notes").performClick()
        onNodeWithText("Created").assertExists()
        assertTrue(onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun renameOpensThePopoverWithTheTitleFocused() = runDesktopComposeUiTest {
        val controller = controller()
        val renamer = Renamer(controller)
        var requests by mutableIntStateOf(0)
        setContent { DrawViewTheme(dynamicColor = false) { DocumentInfoSelector(controller.document, renamer = renamer, renameRequests = requests) } }

        requests++
        waitForIdle()
        field().assertIsFocused()
    }

    @Test
    fun theNamesAreCheckedAsInTheLibrary() {
        assertNotNull(DocumentNames.syntaxError(""))
        assertNotNull(DocumentNames.syntaxError("  "))
        assertNotNull(DocumentNames.syntaxError("a/b"))
        assertNotNull(DocumentNames.syntaxError("a\\b"))
        assertNull(DocumentNames.syntaxError("Physics (2)"))
    }
}
