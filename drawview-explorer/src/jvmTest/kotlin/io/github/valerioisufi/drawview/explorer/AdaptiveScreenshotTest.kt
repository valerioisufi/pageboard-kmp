package io.github.valerioisufi.drawview.explorer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.ui.adaptive.ReferenceWindow
import io.github.valerioisufi.drawview.ui.session.EditorExtension
import io.github.valerioisufi.drawview.ui.session.EditorPanel
import java.util.Locale
import java.util.TimeZone
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Screenshots of the library and of the editor in the reference windows ([ReferenceWindow]), with
 * their input, in the light theme and, on a phone and a desktop, in the dark one: the reference of
 * the adaptive interface (docs/adaptive-ui.md).
 *
 * `./gradlew :drawview-explorer:recordRoborazziJvm` records them in `screenshots/`,
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
            onNodeWithText("Document Background").performClick()
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
        onNodeWithContentDescription("Grid View").performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(1_000)
    }

    /** The "more" menu on a phone: the commands left out of its bars (the imports, under the floating tools). */
    @Test
    fun editorMoreMenu() = capture("editor-more-menu", ReferenceWindow.PHONE, dark = false) { viewModels ->
        openNote(viewModels)
        onNodeWithContentDescription("More options").performClick()
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

    /** A search without results: why, under the field. */
    @Test
    fun librarySearch() = capture("library-search", ReferenceWindow.PHONE, dark = false) {
        onNode(hasSetTextAction()).performTextInput("derivata")
    }

    /** Inside a folder: going up, the path, the empty folder. */
    @Test
    fun libraryFolder() = capture("library-folder", ReferenceWindow.TABLET_PORTRAIT, dark = false) {
        onNodeWithText("Analysis").performClick()
    }

    /** An extension with a panel named after each of [titles], open, listing a few notes. */
    private fun panelsExtension(vararg titles: String) = EditorExtension(
        panels = { _, _ ->
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
    )

    /** Every reference window in the light theme, the phone and the desktop in the dark one too. */
    private fun forEachConfiguration(capture: (ReferenceWindow, dark: Boolean) -> Unit) {
        ReferenceWindow.entries.forEach { capture(it, false) }
        listOf(ReferenceWindow.PHONE, ReferenceWindow.DESKTOP).forEach { capture(it, true) }
    }
}
