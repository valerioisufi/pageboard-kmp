package io.github.valerioisufi.pageboard.explorer

import io.github.valerioisufi.pageboard.ui.extension.LibraryAction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStoreOwner
import io.github.valerioisufi.pageboard.canvas.tool.Tool
import io.github.valerioisufi.pageboard.ui.adaptive.ReferenceWindow
import io.github.valerioisufi.pageboard.ui.extension.EditorExtension
import io.github.valerioisufi.pageboard.ui.extension.EditorPanel
import io.github.valerioisufi.pageboard.ui.screen.EditorActionButton
import io.github.valerioisufi.pageboard.ui.theme.DrawViewIcons
import java.util.Locale
import java.util.TimeZone
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Screenshots of the library and of the editor in the reference windows ([ReferenceWindow]), with
 * their input, in the light theme and, on a phone and a desktop, in the dark one: the reference of
 * the adaptive interface (docs/plans/adaptive-ui.md).
 *
 * `./gradlew :pageboard-explorer:recordRoborazziJvm` records them in `screenshots/`,
 * `verifyRoborazziJvm` compares the interface with them, `compareRoborazziJvm` writes the
 * differences in `build/outputs/roborazzi`.
 */
@OptIn(ExperimentalTestApi::class)
class AdaptiveScreenshotTest {

    @BeforeTest
    fun setUp() {
        // The same strings and dates on every computer
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @Test
    fun library() = forEachConfiguration { window, dark ->
        capture("library", window, dark)
    }

    /** The editor opens in drawing mode: the bar of the tools under the top bar. */
    @Test
    fun editor() = forEachConfiguration { window, dark ->
        capture("editor", window, dark) { viewModels -> openNote(viewModels) }
    }

    @Test
    fun documentBackgroundSheet() = listOf(ReferenceWindow.PHONE, ReferenceWindow.TABLET_LANDSCAPE, ReferenceWindow.DESKTOP).forEach { window ->
        capture("document-background", window, dark = false) { viewModels ->
            openNote(viewModels)
            onNodeWithContentDescription("More options").performClick()
            onNodeWithText("Document background").performClick()
        }
    }

    /** A note clicked with the secondary button of a mouse: its commands in a menu. */
    @Test
    fun libraryContextMenu() = capture("library-context-menu", ReferenceWindow.DESKTOP, dark = false) {
        onNodeWithText(NOTE).performMouseInput { rightClick(Offset(240f, centerY)) }
    }

    /** The pointer resting on a button of the editor: its tooltip, with the shortcut. */
    @Test
    fun editorTooltip() = capture("editor-tooltip", ReferenceWindow.DESKTOP, dark = false) { viewModels ->
        openNote(viewModels)
        onNodeWithContentDescription("Grid view").performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(1_000)
    }

    /** The "more" menu on a phone: the commands left out of its bars (the imports, under the floating tools). */
    @Test
    fun editorMoreMenu() = capture("editor-more-menu", ReferenceWindow.PHONE, dark = false) { viewModels ->
        openNote(viewModels)
        onNodeWithContentDescription("More options").performClick()
    }

    /**
     * The tools floating on a phone with the tool at their end chosen (the hand): undo and redo stay
     * where they are, the tools scroll to the one chosen and fade where there are more.
     */
    @Test
    fun editorFloatingToolsScrolled() = capture("editor-floating-tools-scrolled", ReferenceWindow.PHONE, dark = false) { viewModels ->
        openNote(viewModels)
        runOnIdle { viewModels.controller().selectTool(Tool.PAN) }
        mainClock.advanceTimeBy(1_000)
    }

    /**
     * Three actions of extensions (the lecture, the sharing, the export): beside the title where
     * they leave it room, behind one button on a phone.
     */
    @Test
    fun editorExtensions() = listOf(ReferenceWindow.PHONE, ReferenceWindow.TABLET_SPLIT_SCREEN).forEach { window ->
        capture("editor-extensions", window, dark = false, extension = actionsExtension()) { viewModels -> openNote(viewModels) }
    }

    /** The actions of the extensions on a phone, shown by their button. */
    @Test
    fun editorExtensionsMenu() = capture("editor-extensions-menu", ReferenceWindow.PHONE, dark = false, extension = actionsExtension()) { viewModels ->
        openNote(viewModels)
        onNodeWithContentDescription("Actions").performClick()
    }

    /** A panel of an extension, placed by the editor: beside the canvas where it keeps room enough, else from the bottom. */
    @Test
    fun editorPanel() = ReferenceWindow.entries.forEach { window ->
        capture("editor-panel", window, dark = false, extension = panelsExtension("Notes")) { viewModels -> openNote(viewModels) }
    }

    /** Two panels: side by side on a desktop, in tabs on a tablet in portrait. */
    @Test
    fun editorTwoPanels() = listOf(ReferenceWindow.TABLET_PORTRAIT, ReferenceWindow.DESKTOP).forEach { window ->
        capture("editor-two-panels", window, dark = false, extension = panelsExtension("Notes", "Outline")) { viewModels -> openNote(viewModels) }
    }

    /** The details of a note: a sheet on a phone, a dialog on a desktop. */
    @Test
    fun libraryDetails() = listOf(ReferenceWindow.PHONE, ReferenceWindow.DESKTOP).forEach { window ->
        capture("library-details", window, dark = false) {
            // The last note: "Lecture 1 — Limits"
            onAllNodesWithContentDescription("Info").onLast().performClick()
        }
    }

    /**
     * The long press on a page (the click of the secondary button): paste (a text copied in
     * another application), the page and its background.
     */
    @Test
    fun editorContextMenu() = capture("editor-context-menu", ReferenceWindow.TABLET_LANDSCAPE, dark = false, clipboard = "Copied elsewhere") { viewModels ->
        openNote(viewModels)
        runOnIdle { viewModels.controller().contextMenuPosition = Offset(760f, 420f) }
    }

    /** The grid of the pages: the one looked at ringed, adding a page. */
    @Test
    fun editorPageGrid() = listOf(ReferenceWindow.TABLET_LANDSCAPE, ReferenceWindow.PHONE).forEach { window ->
        capture("editor-page-grid", window, dark = false) { viewModels ->
            openNote(viewModels)
            runOnIdle {
                val controller = viewModels.controller()
                controller.pageManager.addNewPageAfter(0)
                controller.pageManager.addNewPageAfter(1)
                controller.togglePageGrid()
            }
        }
    }

    /** The grid selecting pages: their checks, the bar with how many and the actions on all of them. */
    @Test
    fun editorPageGridSelection() = listOf(
        ReferenceWindow.TABLET_LANDSCAPE to false, ReferenceWindow.PHONE to false, ReferenceWindow.TABLET_LANDSCAPE to true
    ).forEach { (window, dark) ->
        capture("editor-page-grid-selection", window, dark = dark) { viewModels ->
            openNote(viewModels)
            runOnIdle {
                val controller = viewModels.controller()
                controller.pageManager.addNewPageAfter(0)
                controller.pageManager.addNewPageAfter(1)
                controller.togglePageGrid()
                val pages = controller.document.pages
                controller.pageManager.selection.toggle(pages[0].id)
                controller.pageManager.selection.toggle(pages[2].id)
            }
        }
    }

    /** The grid with a page deleted: the message with Undo, the deleted pages open after the others. */
    @Test
    fun editorPageGridDeleted() = listOf(ReferenceWindow.TABLET_LANDSCAPE to false, ReferenceWindow.PHONE to false, ReferenceWindow.TABLET_LANDSCAPE to true).forEach { (window, dark) ->
        capture("editor-page-grid-deleted", window, dark = dark) { viewModels ->
            openNote(viewModels)
            runOnIdle {
                val controller = viewModels.controller()
                controller.pageManager.addNewPageAfter(0)
                controller.pageManager.addNewPageAfter(1)
                controller.pageManager.deletePages(listOf(controller.document.pages[1].id))
                controller.togglePageGrid()
            }
            waitForIdle()
            onNodeWithText("Deleted pages (1)").performScrollTo().performClick()
            waitForIdle()
        }
    }

    /** A search without results: why, under the field. */
    @Test
    fun librarySearch() = capture(
        "library-search",
        ReferenceWindow.PHONE,
        dark = false,
        // The caret blinks: moved, then put back at the end of the field right before the capture,
        // it begins its blink again, shown, the same at every run
        beforeCapture = {
            onNode(hasSetTextAction()).performTextInputSelection(TextRange.Zero)
            onNode(hasSetTextAction()).performTextInputSelection(TextRange("derivata".length))
        }
    ) {
        onNode(hasSetTextAction()).performTextInput("derivata")
    }

    /** Inside a folder: going up, the path, the empty folder. */
    @Test
    fun libraryFolder() = capture("library-folder", ReferenceWindow.TABLET_PORTRAIT, dark = false) {
        onNodeWithText("Analysis").performClick()
    }

    /** The Trash, full (a folder and a note, the message with Undo of both) and empty. */
    @Test
    fun libraryTrash() = listOf(
        ReferenceWindow.PHONE to false, ReferenceWindow.DESKTOP to false, ReferenceWindow.DESKTOP to true, ReferenceWindow.TABLET_PORTRAIT to false
    ).forEach { (window, dark) ->
        capture("library-trash", window, dark = dark) { viewModels ->
            runOnIdle {
                val library = viewModels.library()
                // At once: one message, in the same order at every run
                library.deleteFiles(library.currentDirectoryFiles.filter { it.name == "Physics" || it.name == "Lab report" })
                library.showSection(FileExplorerViewModel.Section.TRASH)
            }
            waitForIdle()
        }
    }

    /**
     * The pane with the actions of an application, as PencilNotes gives them: the prominent one as
     * a wide button, the others each with its name (two may share an icon).
     */
    @Test
    fun libraryActions() {
        val actions = listOf(
            LibraryAction(DrawViewIcons.Link, { "Join a shared document" }, prominent = true) { },
            LibraryAction(DrawViewIcons.Search, { "Search models" }) { },
            LibraryAction(DrawViewIcons.Download, { "Import archive…" }) { },
            LibraryAction(DrawViewIcons.Sparkles, { "External models" }) { },
            LibraryAction(DrawViewIcons.Info, { "About" }) { },
        )
        capture("library-actions", ReferenceWindow.DESKTOP, dark = false, libraryActions = actions)
    }

    @Test
    fun libraryTrashEmpty() = capture("library-trash-empty", ReferenceWindow.PHONE, dark = false) { viewModels ->
        runOnIdle { viewModels.library().showSection(FileExplorerViewModel.Section.TRASH) }
        waitForIdle()
    }

    /**
     * The screens in a window of macOS, whose traffic lights take the top-leading corner (see
     * `docs/plans/title-bar.md`): no button under them, in a wide window and in a narrow one.
     */
    @Test
    fun macWindow() = listOf(ReferenceWindow.DESKTOP, ReferenceWindow.PHONE).forEach { window ->
        val mac = { screen: String, actions: DesktopComposeUiTest.(ViewModelStoreOwner) -> Unit ->
            capture(screen, window, dark = false, extension = actionsExtension(), macWindow = true, actions = actions)
        }
        mac("library") {}
        mac("library-selection") { viewModels -> runOnIdle { viewModels.library().let { it.toggleSelection(it.currentDirectoryFiles.first { file -> file.name == NOTE }) } } }
        mac("library-trash") { viewModels -> runOnIdle { viewModels.library().showSection(FileExplorerViewModel.Section.TRASH) } }
        mac("editor") { viewModels -> openNote(viewModels) }
        mac("editor-page-grid") { viewModels ->
            openNote(viewModels)
            runOnIdle { viewModels.controller().togglePageGrid() }
        }
        mac("editor-page-grid-selection") { viewModels ->
            openNote(viewModels)
            runOnIdle {
                val controller = viewModels.controller()
                controller.togglePageGrid()
                controller.pageManager.selection.toggle(controller.document.pages[0].id)
            }
        }
        mac("document-background") { viewModels ->
            openNote(viewModels)
            onNodeWithContentDescription("More options").performClick()
            onNodeWithText("Document background").performClick()
        }
    }

    /** An extension with three actions in the top bar, as those of PencilNotes. */
    private fun actionsExtension() = EditorExtension {
        toolbarActions {
            EditorActionButton(DrawViewIcons.Sparkles, "Lecture", onClick = {})
            EditorActionButton(DrawViewIcons.UserPlus, "Share", onClick = {})
            EditorActionButton(DrawViewIcons.Share, "Export", onClick = {})
        }
    }

    /** An extension with a panel named after each of [titles], open, listing a few notes. */
    private fun panelsExtension(vararg titles: String) = EditorExtension {
        panels {
            titles.map { title ->
                EditorPanel(id = title, title = { title }, isOpen = { true }, onClose = {}) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (note in listOf("Limits of sequences", "The ε-δ definition", "Limits of functions")) {
                            Text(note)
                        }
                    }
                }
            }
        }
    }

    /** Every reference window in the light theme, the phone and the desktop in the dark one too. */
    private fun forEachConfiguration(capture: (ReferenceWindow, dark: Boolean) -> Unit) {
        ReferenceWindow.entries.forEach { capture(it, false) }
        listOf(ReferenceWindow.PHONE, ReferenceWindow.DESKTOP).forEach { capture(it, true) }
    }
}
