package io.github.valerioisufi.pageboard.handwriting.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.github.takahirom.roborazzi.captureRoboImage
import io.github.valerioisufi.pageboard.handwriting.InkReading
import io.github.valerioisufi.pageboard.ui.theme.LocalCanvasColors
import io.github.valerioisufi.pageboard.ui.theme.PageboardTheme
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The preview of "Convert to text", light and dark: a reading with its other readings, and
 * nothing read.
 *
 * `./gradlew :pageboard-handwriting-ui:recordRoborazziJvm` records them in `screenshots/` (in the
 * image of `tools/screenshots` only).
 */
@OptIn(ExperimentalTestApi::class)
class ConversionScreenshotTest {
    @BeforeTest
    fun setUp() {
        Locale.setDefault(Locale.US)
    }

    private fun capture(name: String, reading: InkReading?, width: Int = 560, height: Int = 520, themes: List<Boolean> = listOf(false, true)) {
        for (dark in themes) {
            runDesktopComposeUiTest(width = width, height = height) {
                setContent {
                    PageboardTheme(darkTheme = dark, dynamicColor = false) {
                        Box(modifier = Modifier.fillMaxSize().background(LocalCanvasColors.current.desk))
                        ConversionDialog(
                            read = true,
                            reading = reading,
                            text = reading?.text.orEmpty(),
                            onTextChange = {},
                            canConvert = true,
                            onConvert = {},
                            onDismiss = {}
                        )
                    }
                }
                waitForIdle()
                captureToImage().captureRoboImage("screenshots/$name-${if (dark) "dark" else "light"}.png")
            }
        }
    }

    @Test
    fun aReadingWithItsOtherReadings() = capture(
        "convert-to-text",
        InkReading("a continuous function on a closed interval", listOf("a continuous function on a closed interual", "a continuous junction on a closed interval"))
    )

    /**
     * A phone in landscape, 411 dp high: the readings scroll between the title and the buttons,
     * which stay in sight.
     */
    @Test
    fun inALowWindow() = capture(
        "convert-to-text-low",
        InkReading("a continuous function on a closed interval", listOf("a continuous function on a closed interual", "a continuous junction on a closed interval", "a continuous function on a cloud interval")),
        width = 891, height = 411, themes = listOf(false)
    )

    @Test
    fun nothingRead() = capture("convert-to-text-nothing-read", null)
}
