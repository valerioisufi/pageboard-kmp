package io.github.valerioisufi.drawview.lecture.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.lecture.AssistantSettingsStore
import io.github.valerioisufi.drawview.lecture.LectureAssistant
import io.github.valerioisufi.drawview.lecture.LectureLibrary
import io.github.valerioisufi.drawview.lecture.testing.FakeMicrophone
import io.github.valerioisufi.drawview.lecture.testing.MemorySecrets
import io.github.valerioisufi.drawview.ui.theme.DrawViewTheme
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers

/**
 * The settings of the assistant in their real dialog on a desktop: as wide as they are laid out
 * for, with the choice of each request beside its name (docs/ui-fit.md).
 */
@OptIn(ExperimentalTestApi::class)
class AssistantSettingsDialogTest {
    private val root: File = Files.createTempDirectory("assistant-settings").toFile()

    @BeforeTest
    fun setUp() {
        Locale.setDefault(Locale.US)
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun theDialogIsAsWideAsItsSectionsAndItsRequests() {
        val store = AssistantSettingsStore(File(root, "settings.json").absolutePath, MemorySecrets())
        val assistant = LectureAssistant(
            settingsStore = store,
            captureFactory = { FakeMicrophone() },
            audioOutput = null,
            library = LectureLibrary(File(root, "lectures").absolutePath),
            stateContext = Dispatchers.Unconfined
        )
        runDesktopComposeUiTest(width = 1280, height = 900) {
            setContent {
                DrawViewTheme(darkTheme = false, dynamicColor = false) {
                    AssistantSettingsDialog(store, assistant.localModels, onDismiss = {})
                }
            }
            val dialog = onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)).getBoundsInRoot()
            assertEquals(940.dp, dialog.right - dialog.left)
            // The choice of a request in the right half of its section, beside the name of the request
            val section = dialog.left + 240.dp
            val choice = onAllNodesWithText("Online").onFirst().getBoundsInRoot()
            assertTrue(choice.left > section + (dialog.right - section) / 2, "the choice at ${choice.left}, the section from $section")
        }
    }
}
