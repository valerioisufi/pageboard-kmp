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
import io.github.valerioisufi.drawview.ui.theme.DrawViewTheme
import io.github.valerioisufi.drawview.ui.theme.LocalCanvasColors
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Screenshots of the export as its dialog draws it (the dialog itself opens in a window the
 * screenshots do not take): the choices, the image of a page, exporting, done, failed
 * (docs/ui-design.md).
 *
 * `./gradlew :drawview-export:recordRoborazziJvm` records them in `screenshots/`.
 */
@OptIn(ExperimentalTestApi::class)
class ExportScreenshotTest {

    @BeforeTest
    fun setUp() {
        Locale.setDefault(Locale.US)
    }

    @Test
    fun states() = listOf(false, true).forEach { dark ->
        runDesktopComposeUiTest(width = 1360, height = 1000) {
            setContent {
                DrawViewTheme(darkTheme = dark, dynamicColor = false) {
                    Box(modifier = Modifier.fillMaxSize().background(LocalCanvasColors.current.desk).padding(24.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                                Sheet { Body(ExportState.Choosing, ExportFormat.PDF) }
                                Sheet { Body(ExportState.Choosing, ExportFormat.PNG, backgroundPattern = false) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                                Sheet { Body(ExportState.Running(0.6f), ExportFormat.PDF) }
                                Sheet { Body(ExportState.Done(skipped = 2), ExportFormat.PDF) }
                                Sheet { Body(ExportState.Failed, ExportFormat.PDF) }
                            }
                        }
                    }
                }
            }
            waitForIdle()
            captureToImage().captureRoboImage("screenshots/export-states-${if (dark) "dark" else "light"}.png")
        }
    }

    @Composable
    private fun Body(state: ExportState, format: ExportFormat, backgroundPattern: Boolean = true) = ExportBody(
        state = state,
        format = format,
        onFormat = {},
        onlyPageOnScreen = false,
        onOnlyPageOnScreen = {},
        backgroundPattern = backgroundPattern,
        onBackgroundPattern = {},
        withPageOnScreen = true
    )

    /** The dialog with its title (its buttons are the ones of the sheet). */
    @Composable
    private fun Sheet(content: @Composable () -> Unit) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(28.dp))
                .padding(vertical = 24.dp)
        ) {
            Text(
                "Export “Lecture 1 — Limits”",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp)
            )
            content()
        }
    }
}
