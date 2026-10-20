package io.github.valerioisufi.drawview.export.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.github.valerioisufi.drawview.export.FolderExportFormat
import io.github.valerioisufi.drawview.ui.theme.DrawViewTheme
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class FolderExportUiTest {

    @BeforeTest
    fun setUp() {
        Locale.setDefault(Locale.US)
    }

    @Test
    fun displaysChoosingStateWithNoteCount() = runDesktopComposeUiTest {
        setContent {
            DrawViewTheme {
                FolderExportBody(
                    state = ExportState.Choosing,
                    format = FolderExportFormat.ZIP,
                    onFormat = {},
                    includeSubfolders = true,
                    onIncludeSubfolders = {},
                    backgroundPattern = true,
                    onBackgroundPattern = {},
                    noteCount = 5
                )
            }
        }
        onNodeWithText("ZIP archive (individual PDFs)").assertIsDisplayed()
        onNodeWithText("Single combined PDF").assertIsDisplayed()
        onNodeWithText("Include subfolders").assertIsDisplayed()
        onNodeWithText("Lines, grid and dots of the background").assertIsDisplayed()
        onNodeWithText("Notes to export: 5").assertIsDisplayed()
    }

    @Test
    fun displaysEmptyStateWhenZeroNotes() = runDesktopComposeUiTest {
        setContent {
            DrawViewTheme {
                FolderExportBody(
                    state = ExportState.Choosing,
                    format = FolderExportFormat.ZIP,
                    onFormat = {},
                    includeSubfolders = false,
                    onIncludeSubfolders = {},
                    backgroundPattern = true,
                    onBackgroundPattern = {},
                    noteCount = 0
                )
            }
        }
        onNodeWithText("No notes to export in this folder").assertIsDisplayed()
    }

    @Test
    fun displaysRunningState() = runDesktopComposeUiTest {
        setContent {
            DrawViewTheme {
                FolderExportBody(
                    state = ExportState.Running(0.42f),
                    format = FolderExportFormat.ZIP,
                    onFormat = {},
                    includeSubfolders = true,
                    onIncludeSubfolders = {},
                    backgroundPattern = true,
                    onBackgroundPattern = {},
                    noteCount = 3
                )
            }
        }
        onNodeWithText("Exporting…").assertIsDisplayed()
    }

    @Test
    fun displaysDoneStateWithSkippedCount() = runDesktopComposeUiTest {
        setContent {
            DrawViewTheme {
                FolderExportBody(
                    state = ExportState.Done(skipped = 1),
                    format = FolderExportFormat.ZIP,
                    onFormat = {},
                    includeSubfolders = true,
                    onIncludeSubfolders = {},
                    backgroundPattern = true,
                    onBackgroundPattern = {},
                    noteCount = 3
                )
            }
        }
        onNodeWithText("The folder has been exported.").assertIsDisplayed()
        onNodeWithText("Elements left out because their file is missing: 1").assertIsDisplayed()
    }

    @Test
    fun displaysFailedState() = runDesktopComposeUiTest {
        setContent {
            DrawViewTheme {
                FolderExportBody(
                    state = ExportState.Failed,
                    format = FolderExportFormat.ZIP,
                    onFormat = {},
                    includeSubfolders = true,
                    onIncludeSubfolders = {},
                    backgroundPattern = true,
                    onBackgroundPattern = {},
                    noteCount = 3
                )
            }
        }
        onNodeWithText("The folder could not be exported.").assertIsDisplayed()
    }
}
