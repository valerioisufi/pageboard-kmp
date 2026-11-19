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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.takahirom.roborazzi.captureRoboImage
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.canvas.controller.ShapeToolStyle
import io.github.valerioisufi.drawview.model.shape.ShapeDash
import io.github.valerioisufi.drawview.model.shape.ShapeKind
import io.github.valerioisufi.drawview.ui.screen.ShapeSettings
import io.github.valerioisufi.drawview.ui.screen.ShapeToolTarget
import io.github.valerioisufi.drawview.canvas.controller.PlacementRequest
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.placement.BesideAnchor
import io.github.valerioisufi.drawview.model.placement.PlacementBlock
import io.github.valerioisufi.drawview.ui.editor.PlacementBar
import io.github.valerioisufi.drawview.ui.editor.TextImportOptions
import io.github.valerioisufi.drawview.ui.editor.previewOf
import io.github.valerioisufi.drawview.canvas.controller.TextImport
import io.github.valerioisufi.drawview.canvas.controller.TextImportPlace
import io.github.valerioisufi.drawview.canvas.tool.Tool
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.geometry.Dimension
import io.github.valerioisufi.drawview.model.geometry.mm
import io.github.valerioisufi.drawview.ui.adaptive.AdaptiveSheet
import io.github.valerioisufi.drawview.ui.command.MenuDivider
import io.github.valerioisufi.drawview.ui.command.MenuItem
import io.github.valerioisufi.drawview.ui.command.MenuLabel
import io.github.valerioisufi.drawview.ui.components.DocumentInfoSelector
import io.github.valerioisufi.drawview.ui.components.HybridScale
import io.github.valerioisufi.drawview.ui.components.MarkdownText
import io.github.valerioisufi.drawview.ui.components.PageTemplateConfigurator
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
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Screenshots of the components that open over the screens (the popovers of the tools, the
 * menus), which the screenshots of the screens do not draw (docs/plans/ui-design.md).
 *
 * `./gradlew :pageboard-ui:recordRoborazziJvm` records them in `screenshots/`.
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

    /**
     * The Markdown of a note out of the page (the panel of the lecture assistant): a heading, code in
     * the line, a block with the numbers of its lines, a list with a block under an item.
     */
    @Test
    fun markdownText() = capture("markdown-text", width = 520, height = 560) {
        Column(
            modifier = Modifier.width(460.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)).padding(16.dp)
        ) {
            MarkdownText(
                "# Pointers\nA pointer holds an address: `&x` takes it, `*p` reads it.\n" +
                    "```c showLineNumbers\nint x = 42;\nint *p = &x;\nprintf(\"%d\\n\", *p); // the value pointed to, 42\n```\n" +
                    "- Compile it:\n  ```sh\n  gcc main.c -o main\n  ```\n- Then run `./main`.",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium
            )
        }
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

    /**
     * The popovers of the shapes: the tool with a box chosen (its fill), with an arrow (its
     * heads), and the style of shapes selected.
     */
    @Test
    fun shapePopovers() = listOf(false, true).forEach { dark ->
        capture("shape-popovers-${if (dark) "dark" else "light"}", width = 1000, height = 760, dark = dark) {
            val box = remember { DrawViewController(document = DrawDocument(), coroutineScope = CoroutineScope(Dispatchers.Unconfined)) }
            val arrow = remember {
                DrawViewController(document = DrawDocument(), coroutineScope = CoroutineScope(Dispatchers.Unconfined)).apply {
                    shapeInserter.updateStyle(ShapeToolStyle(kind = ShapeKind.LINE, heads = 1, stroke = 0xFF1F5FBF.toInt(), widthMm = 0.7f))
                }
            }
            val dashed = remember {
                DrawViewController(document = DrawDocument(), coroutineScope = CoroutineScope(Dispatchers.Unconfined)).apply {
                    shapeInserter.updateStyle(ShapeToolStyle(kind = ShapeKind.ELLIPSE, stroke = 0xFFD23B3B.toInt(), dash = ShapeDash.DASHED, fill = 0x33D23B3B))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                Popover { ShapeSettings(ShapeToolTarget(box), "Shapes", withHint = true) }
                Popover { ShapeSettings(ShapeToolTarget(arrow), "Shapes", withHint = false) }
                Popover { ShapeSettings(ShapeToolTarget(dashed), "Shape style", withHint = false) }
            }
        }
    }

    /** The configurator of a page: a dialog with the preview beside the controls, a sheet on a phone (a custom size too small). */
    @Test
    fun pageConfigurator() {
        capture("page-configurator-dialog", width = 808, height = 640) {
            // As the editor opens it
            AdaptiveSheet(onDismissRequest = {}, maxWidth = 760.dp) {
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

    /**
     * The title of the document opened by "Rename…": its name selected in the field, then a name
     * the library would refuse, with its error.
     */
    @Test
    fun documentRename() = listOf(false, true).forEach { invalid ->
        runDesktopComposeUiTest(width = 420, height = 560) {
            val controller = DrawViewController(document = DrawDocument(title = "Lecture 1 — Limits"), coroutineScope = CoroutineScope(Dispatchers.Unconfined))
            var requests by mutableIntStateOf(0)
            setContent {
                // Light whatever the theme of the host
                DrawViewTheme(darkTheme = false, dynamicColor = false) {
                    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
                        DocumentInfoSelector(controller.document, subtitle = "1 page", location = "Analysis", renamer = { true }, renameRequests = requests)
                    }
                }
            }
            requests++
            waitForIdle()
            if (invalid) onNode(hasSetTextAction()).performTextReplacement("Limits/2")
            waitForIdle()
            captureToImage().captureRoboImage("screenshots/document-rename${if (invalid) "-error" else ""}.png")
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

    /** The bar of a pending placement: a note at the second of its five places proposed, and a note going on a new page. */
    @Test
    fun placementBar() = capture("placement-bar", width = 560, height = 230) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(false, true).forEach { full ->
                val controller = remember {
                    DrawViewController(document = DrawDocument(), coroutineScope = CoroutineScope(Dispatchers.Unconfined)).apply {
                        viewport.setSize(800f, 1200f)
                        if (full) document.pages[0].add(DrawText().apply { width = 210f; height = 297f; text = "Full" })
                        val spots = placementManager.spotsFor(0, PlacementBlock(90f) { 20f }, BesideAnchor(null))
                        placementManager.start(PlacementRequest(DrawText(), onPlace = {}, spots = spots, label = "Bayes' theorem"))
                        if (!full) placementManager.next()
                    }
                }
                PlacementBar(controller)
            }
        }
    }

    /** The choices of the import of a text copied from a PDF: by topic, cleaned, its preview, the notes of the assistant offered. */
    @Test
    fun textImport() = capture("text-import", width = 600, height = 700) {
        val import = TextImport(
            "LIMITI\n\nUna funzione ha limite L in a se i suoi valori si avvi-\ncinano a L quando x si avvicina ad a.\n\n• unicità del limite\n• permanenza del segno\n\n2.1 Limiti notevoli\n\nIl più noto è sin(x)/x.",
            pageIndex = 2, isMarkdown = false
        )
        Box(modifier = Modifier.width(552.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(28.dp)).padding(vertical = 24.dp)) {
            TextImportOptions(
                byTopic = true, page = 2, place = TextImportPlace.TOPIC, onPlace = {}, clean = true, onClean = {},
                preview = previewOf(import, clean = true), asNotes = {}
            )
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
            MenuItem("Snap to guides", onClick = {}, icon = rememberVectorPainter(DrawViewIcons.Magnet), checked = true)
            MenuItem("Delete", onClick = {}, icon = rememberVectorPainter(DrawViewIcons.Trash), shortcut = "⌫", destructive = true)
        }
    }
}
