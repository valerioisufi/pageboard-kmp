package io.github.valerioisufi.drawview.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.takahirom.roborazzi.captureRoboImage
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.core.model.DrawDocument
import io.github.valerioisufi.drawview.core.model.Tool
import io.github.valerioisufi.drawview.ui.command.MenuDivider
import io.github.valerioisufi.drawview.ui.command.MenuItem
import io.github.valerioisufi.drawview.ui.command.MenuLabel
import io.github.valerioisufi.drawview.ui.components.HybridScale
import io.github.valerioisufi.drawview.ui.components.PageTemplateConfigurator
import io.github.valerioisufi.drawview.core.geometry.Dimension
import io.github.valerioisufi.drawview.core.geometry.mm
import io.github.valerioisufi.drawview.core.model.PageBackground
import io.github.valerioisufi.drawview.ui.screen.EraserSettings
import io.github.valerioisufi.drawview.ui.screen.InkSettings
import io.github.valerioisufi.drawview.ui.screen.LassoSettings
import io.github.valerioisufi.drawview.ui.screen.MarkupSettings
import io.github.valerioisufi.drawview.ui.screen.ReadOnlyBadge
import io.github.valerioisufi.drawview.ui.screen.TextToolSettings
import io.github.valerioisufi.drawview.ui.theme.DrawViewElevation
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import io.github.valerioisufi.drawview.ui.theme.DrawViewTheme
import io.github.valerioisufi.drawview.ui.theme.InkPalette
import io.github.valerioisufi.drawview.ui.theme.LocalCanvasColors
import io.github.valerioisufi.drawview.ui.theme.elevation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Screenshots of the components that open over the screens (the popovers of the tools, the
 * menus), which the screenshots of the screens do not draw (docs/ui-design.md).
 *
 * `./gradlew :drawview-ui:recordRoborazziJvm` records them in `screenshots/`.
 */
@OptIn(ExperimentalTestApi::class)
class ComponentScreenshotTest {

    @BeforeTest
    fun setUp() {
        Locale.setDefault(Locale.US)
    }

    private fun capture(name: String, width: Int, height: Int, dark: Boolean = false, content: @Composable () -> Unit) =
        runDesktopComposeUiTest(width = width, height = height) {
            setContent {
                DrawViewTheme(darkTheme = dark, dynamicColor = false) {
                    Box(modifier = Modifier.fillMaxSize().background(LocalCanvasColors.current.desk).padding(24.dp)) { content() }
                }
            }
            waitForIdle()
            captureToImage().captureRoboImage("screenshots/$name.png")
        }

    /** The popovers of the tools: the pen and the highlighter, the eraser, the lasso, the text, the marks of the PDFs. */
    @Test
    fun toolPopovers() = listOf(false, true).forEach { dark ->
        capture("tool-popovers-${if (dark) "dark" else "light"}", width = 1320, height = 1040, dark = dark) {
            val controller = remember { DrawViewController(document = DrawDocument(), coroutineScope = CoroutineScope(Dispatchers.Unconfined)) }
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                    Popover { InkSettings(controller, Tool.INK_PEN, "Pen", InkPalette.inks, HybridScale(0.1f..15f, 3f, 0.5f)) }
                    Popover { InkSettings(controller, Tool.INK_HIGHLIGHTER, "Highlighter", InkPalette.highlights, HybridScale(1f..30f, 8f, 0.3f)) }
                    Popover { EraserSettings(controller) }
                    Popover { LassoSettings(controller) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                    Popover { TextToolSettings(controller) }
                    Popover { MarkupSettings(controller) }
                    Menu()
                }
            }
        }
    }

    /** The configurator of a page: a dialog with the preview beside the controls, a sheet on a phone (a custom size too small). */
    @Test
    fun pageConfigurator() {
        capture("page-configurator-dialog", width = 808, height = 640) {
            Box(modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(28.dp)).padding(vertical = 24.dp)) {
                PageTemplateConfigurator(subtitle = "Page 3 of “Lecture 1 — Limits”", initialBackground = PageBackground.Ruled(lineColor = 0x6664B5F6), onApply = { _, _ -> }, onCancel = {})
            }
        }
        capture("page-configurator-sheet", width = 411, height = 900) {
            Box(modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(28.dp)).padding(vertical = 24.dp)) {
                PageTemplateConfigurator(
                    initialDimension = Dimension(160f.mm, 40f.mm),
                    initialBackground = PageBackground.Ruled(lineColor = 0x6664B5F6),
                    onApply = { _, _ -> },
                    onCancel = {}
                )
            }
        }
    }

    /** A document read only: why there are no tools, in the top bar and floating on a phone. */
    @Test
    fun readOnly() = capture("read-only", width = 560, height = 180) {
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(modifier = Modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(8.dp)) {
                ReadOnlyBadge("Valerio’s Mac decides who edits", floating = false)
            }
            ReadOnlyBadge("Valerio’s Mac decides who edits", floating = true)
        }
    }

    /** A popover as the editor opens it under a tool. */
    @Composable
    private fun Popover(content: @Composable ColumnScope.() -> Unit) {
        val shape = RoundedCornerShape(24.dp)
        Column(
            modifier = Modifier
                .width(300.dp)
                .elevation(DrawViewElevation.Level3, shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            content = content
        )
    }

    /** A menu: a label, items with their icons, a state on, shortcuts, a destructive item. */
    @Composable
    private fun Menu() {
        val shape = RoundedCornerShape(16.dp)
        Column(
            modifier = Modifier
                .width(260.dp)
                .elevation(DrawViewElevation.Level3, shape)
                .background(MaterialTheme.colorScheme.surfaceContainer, shape)
                .padding(8.dp)
        ) {
            MenuLabel("Sort by")
            MenuItem("Name", onClick = {}, icon = rememberVectorPainter(DrawViewIcons.Check), selected = true, alignWithIcons = true)
            MenuItem("Date created", onClick = {}, alignWithIcons = true)
            MenuDivider()
            MenuItem("Rename", onClick = {}, icon = rememberVectorPainter(DrawViewIcons.Pencil), shortcut = "⌘R")
            MenuItem("Snap to Guides", onClick = {}, icon = rememberVectorPainter(DrawViewIcons.Magnet), checked = true)
            MenuItem("Delete", onClick = {}, icon = rememberVectorPainter(DrawViewIcons.Trash), shortcut = "⌫", destructive = true)
        }
    }
}
