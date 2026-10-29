package io.github.valerioisufi.drawview.export.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.takahirom.roborazzi.captureRoboImage
import io.github.valerioisufi.drawview.export.ExportContents
import io.github.valerioisufi.drawview.export.ExportFormat
import io.github.valerioisufi.drawview.export.ExportScope
import io.github.valerioisufi.drawview.export.FolderDocumentItem
import io.github.valerioisufi.drawview.store.DocumentSummary
import io.github.valerioisufi.drawview.ui.theme.DrawViewTheme
import io.github.valerioisufi.drawview.ui.theme.LocalCanvasColors
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Screenshots of the export sheet as it draws its content (the sheet itself opens in a window
 * the screenshots do not take): a note from the editor, a page range, a folder, an empty folder
 * (docs/export-ux.md).
 *
 * `./gradlew :drawview-export-ui:recordRoborazziJvm` records them in `screenshots/`.
 */
@OptIn(ExperimentalTestApi::class)
class ExportScreenshotTest {

    @BeforeTest
    fun setUp() {
        Locale.setDefault(Locale.US)
    }

    private fun summary(name: String, pages: Int) = DocumentSummary(id = name, name = name, createdAt = 0, modifiedAt = 0, lastOpenedAt = 0, pageCount = pages)

    private fun note(configure: ExportSheetState.() -> Unit = {}) =
        ExportSheetState(ExportScope.Pages("lecture"), ExportPreferences(), PageOnScreen("p3", 2)).apply {
            contents = ExportContents(scope, "Lecture 1 — Limits", listOf(FolderDocumentItem(summary("Lecture 1 — Limits", 12), "")), 12)
            configure()
        }

    private fun folder(notes: Int) = ExportSheetState(ExportScope.Folder("physics"), ExportPreferences()).apply {
        val documents = (1..notes).map { FolderDocumentItem(summary("Note $it", 3), "Note $it.pdf") }
        contents = ExportContents(scope, "Physics", documents, notes * 3)
    }

    @Test
    fun states() = listOf(false, true).forEach { dark ->
        runDesktopComposeUiTest(width = 1440, height = 1320) {
            setContent {
                DrawViewTheme(darkTheme = dark, dynamicColor = false) {
                    Box(modifier = Modifier.fillMaxSize().background(LocalCanvasColors.current.desk).padding(24.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                                Sheet(note())
                                Sheet(note { format = ExportFormat.IMAGES; pages = PageChoice.RANGE; range = "3-20"; backgroundPattern = false })
                                Sheet(folder(14))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                                Sheet(folder(0))
                                Sheet(note { format = ExportFormat.IMAGES; pages = PageChoice.ON_SCREEN })
                            }
                        }
                    }
                }
            }
            waitForIdle()
            captureToImage().captureRoboImage("screenshots/export-sheet-${if (dark) "dark" else "light"}.png")
        }
    }

    /** The sheet with its title (its buttons are the ones of the sheet). */
    @Composable
    private fun Sheet(state: ExportSheetState) {
        Column(
            modifier = Modifier
                .width(440.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(28.dp))
                .padding(vertical = 24.dp)
        ) {
            Text(
                "Export",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp)
            )
            ExportSheetContent(state, thumbnail = null, fallbackName = "Home", onSubmit = {})
        }
    }
}
