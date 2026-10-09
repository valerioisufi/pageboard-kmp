package io.github.valerioisufi.drawview.ui.screen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Title
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.core.model.TextAlignment
import io.github.valerioisufi.drawview.core.model.Tool
import io.github.valerioisufi.drawview.core.text.RichTextEditing.Format
import io.github.valerioisufi.drawview.core.text.TextSizes
import io.github.valerioisufi.drawview.ui.command.Command
import io.github.valerioisufi.drawview.ui.command.CommandGroup
import io.github.valerioisufi.drawview.ui.command.Shortcut
import io.github.valerioisufi.drawview.ui.generated.resources.Res
import io.github.valerioisufi.drawview.ui.generated.resources.about_dialog_title
import io.github.valerioisufi.drawview.ui.generated.resources.command_close
import io.github.valerioisufi.drawview.ui.generated.resources.command_fit_width
import io.github.valerioisufi.drawview.ui.generated.resources.command_next_page
import io.github.valerioisufi.drawview.ui.generated.resources.command_previous_page
import io.github.valerioisufi.drawview.ui.generated.resources.command_previous_tool
import io.github.valerioisufi.drawview.ui.generated.resources.command_snapping
import io.github.valerioisufi.drawview.ui.generated.resources.command_zoom_in
import io.github.valerioisufi.drawview.ui.generated.resources.command_zoom_out
import io.github.valerioisufi.drawview.ui.generated.resources.common_action_copy
import io.github.valerioisufi.drawview.ui.generated.resources.common_action_cut
import io.github.valerioisufi.drawview.ui.generated.resources.common_action_delete
import io.github.valerioisufi.drawview.ui.generated.resources.common_action_paste
import io.github.valerioisufi.drawview.ui.generated.resources.common_action_redo
import io.github.valerioisufi.drawview.ui.generated.resources.common_action_undo
import io.github.valerioisufi.drawview.ui.generated.resources.context_menu_action_add_page
import io.github.valerioisufi.drawview.ui.generated.resources.draw_menu_document_background
import io.github.valerioisufi.drawview.ui.generated.resources.draw_menu_stylus_only
import io.github.valerioisufi.drawview.ui.generated.resources.draw_toolbar_action_decrease_font_size
import io.github.valerioisufi.drawview.ui.generated.resources.draw_toolbar_action_draw
import io.github.valerioisufi.drawview.ui.generated.resources.draw_toolbar_action_eraser
import io.github.valerioisufi.drawview.ui.generated.resources.draw_toolbar_action_grid_view
import io.github.valerioisufi.drawview.ui.generated.resources.draw_toolbar_action_highlighter
import io.github.valerioisufi.drawview.ui.generated.resources.draw_toolbar_action_import_image
import io.github.valerioisufi.drawview.ui.generated.resources.draw_toolbar_action_import_pdf
import io.github.valerioisufi.drawview.ui.generated.resources.draw_toolbar_action_increase_font_size
import io.github.valerioisufi.drawview.ui.generated.resources.draw_toolbar_action_ink_pen
import io.github.valerioisufi.drawview.ui.generated.resources.draw_toolbar_action_lasso
import io.github.valerioisufi.drawview.ui.generated.resources.draw_toolbar_action_pan
import io.github.valerioisufi.drawview.ui.generated.resources.draw_toolbar_action_text
import io.github.valerioisufi.drawview.ui.generated.resources.draw_toolbar_action_text_markup
import io.github.valerioisufi.drawview.ui.generated.resources.icon_ink_eraser
import io.github.valerioisufi.drawview.ui.generated.resources.icon_ink_highlighter
import io.github.valerioisufi.drawview.ui.generated.resources.icon_ink_pen
import io.github.valerioisufi.drawview.ui.generated.resources.icon_lasso_select
import io.github.valerioisufi.drawview.ui.generated.resources.icon_pan_tool
import io.github.valerioisufi.drawview.ui.generated.resources.icon_text_fields
import io.github.valerioisufi.drawview.ui.generated.resources.icon_text_markup
import io.github.valerioisufi.drawview.ui.generated.resources.text_align_center
import io.github.valerioisufi.drawview.ui.generated.resources.text_align_end
import io.github.valerioisufi.drawview.ui.generated.resources.text_align_start
import io.github.valerioisufi.drawview.ui.generated.resources.text_editor_action_bold
import io.github.valerioisufi.drawview.ui.generated.resources.text_editor_action_code
import io.github.valerioisufi.drawview.ui.generated.resources.text_editor_action_heading
import io.github.valerioisufi.drawview.ui.generated.resources.text_editor_action_italic
import io.github.valerioisufi.drawview.ui.generated.resources.text_editor_action_math
import io.github.valerioisufi.drawview.ui.generated.resources.text_editor_action_strikethrough
import io.github.valerioisufi.drawview.ui.platform.PencilAction
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * What the commands of the editor ask of the screen showing it: the pickers of the files and the
 * dialogs it owns.
 */
internal class EditorScreenActions(
    val close: () -> Unit,
    val importPdf: () -> Unit,
    val importImage: () -> Unit,
    val editDocumentBackground: () -> Unit,
    val showAbout: () -> Unit
)

/**
 * The commands of the editor of [controller]: the toolbar, the menu bar, the shortcuts and the
 * tooltips show the same ones. Each asks its expert whether it can run (the history, the
 * selections, the mode of the editor) and runs through it.
 */
@Stable
class EditorCommands internal constructor(
    private val controller: DrawViewController,
    private val clipboard: ClipboardManager,
    private val actions: EditorScreenActions
) {
    private val writable = { !controller.isReadOnly }

    // ----- File -----

    val close = Command("close", label(Res.string.command_close), CommandGroup.FILE, shortcuts = listOf(primary(Key.W))) { actions.close() }

    // ----- Edit -----

    val undo = Command(
        "undo", label(Res.string.common_action_undo), CommandGroup.EDIT,
        icon = { rememberVectorPainter(Icons.AutoMirrored.Outlined.Undo) },
        shortcuts = listOf(primary(Key.Z)),
        isEnabled = { controller.canUndo }
    ) { controller.undo() }

    val redo = Command(
        "redo", label(Res.string.common_action_redo), CommandGroup.EDIT,
        icon = { rememberVectorPainter(Icons.AutoMirrored.Outlined.Redo) },
        shortcuts = listOf(Shortcut(Key.Z, primary = true, shift = true), primary(Key.Y)),
        isEnabled = { controller.canRedo }
    ) { controller.redo() }

    /** The lasso selection, if any (the elements of the pages). */
    private val hasSelection get() = controller.isDrawingMode && controller.selectionManager.currentSelection != null

    /** The selected text of the PDFs or the selected mark, if any. */
    private val hasTextSelection get() = controller.textMarkupManager.hasSelection

    /**
     * The text of the texts copied last in the editor, also put in the clipboard of the system:
     * pasted again, it pastes the elements copied with them (another text came from elsewhere).
     */
    private var copiedText: String? = null

    /** Puts the text of the texts selected in the clipboard of the system, for the other applications. */
    private fun shareSelectedText() {
        copiedText = controller.selectionManager.selectedPlainText?.also { clipboard.setText(AnnotatedString(it)) }
    }

    val cut = Command(
        "cut", label(Res.string.common_action_cut), CommandGroup.EDIT, section = 1,
        icon = { rememberVectorPainter(Icons.Default.ContentCut) },
        shortcuts = listOf(primary(Key.X)),
        isEnabled = { hasSelection && writable() }
    ) {
        shareSelectedText()
        controller.selectionManager.cutSelection()
    }

    val copy = Command(
        "copy", label(Res.string.common_action_copy), CommandGroup.EDIT, section = 1,
        icon = { rememberVectorPainter(Icons.Default.ContentCopy) },
        shortcuts = listOf(primary(Key.C)),
        isEnabled = { hasTextSelection || hasSelection }
    ) {
        val text = controller.textMarkupManager.selectedText
        if (hasTextSelection) {
            if (!text.isNullOrEmpty()) clipboard.setText(AnnotatedString(text))
        } else {
            shareSelectedText()
            controller.selectionManager.copySelection()
        }
    }

    val paste = Command(
        "paste", label(Res.string.common_action_paste), CommandGroup.EDIT, section = 1,
        icon = { rememberVectorPainter(Icons.Default.ContentPaste) },
        shortcuts = listOf(primary(Key.V)),
        isEnabled = { controller.isDrawingMode && writable() }
    ) { pasteAt(null) }

    /**
     * Pastes at [position] of the canvas (the middle of the page shown when `null`): a text copied
     * in another application becomes a new text; else what was copied here.
     */
    fun pasteAt(position: Offset?) {
        val text = clipboard.getText()?.text
        if (!text.isNullOrBlank() && text != copiedText) {
            controller.textEditorManager.pasteText(text, position)
        } else {
            controller.selectionManager.pasteSelection(position)
        }
    }

    /** Whether there is something to paste: elements copied here or a text of the clipboard. */
    fun canPaste(): Boolean = controller.selectionManager.canPaste() || clipboard.getText()?.text?.isNotBlank() == true

    val delete = Command(
        "delete", label(Res.string.common_action_delete), CommandGroup.EDIT, section = 1,
        icon = { rememberVectorPainter(Icons.Default.Delete) },
        shortcuts = listOf(Shortcut(Key.Backspace), Shortcut(Key.Delete)),
        isEnabled = { (hasSelection || controller.textMarkupManager.selectedMarkup != null) && writable() }
    ) {
        if (controller.textMarkupManager.selectedMarkup != null) {
            controller.textMarkupManager.deleteSelectedMarkup()
        } else {
            controller.selectionManager.deleteSelection()
        }
    }

    // ----- View -----

    val zoomIn = Command(
        "zoom-in", label(Res.string.command_zoom_in), CommandGroup.VIEW,
        // ⌘+ is ⌘= (with or without ⇧) on many keyboards, + on others and on the keypad
        shortcuts = listOf(primary(Key.Plus), primary(Key.Equals), Shortcut(Key.Equals, primary = true, shift = true), primary(Key.NumPadAdd))
    ) { controller.zoomIn() }

    val zoomOut = Command(
        "zoom-out", label(Res.string.command_zoom_out), CommandGroup.VIEW,
        shortcuts = listOf(primary(Key.Minus), primary(Key.NumPadSubtract))
    ) { controller.zoomOut() }

    val fitWidth = Command(
        "fit-width", label(Res.string.command_fit_width), CommandGroup.VIEW,
        shortcuts = listOf(primary(Key.Zero))
    ) { controller.fitWidth() }

    val nextPage = Command(
        "next-page", label(Res.string.command_next_page), CommandGroup.VIEW, section = 1,
        shortcuts = listOf(Shortcut(Key.PageDown)),
        isEnabled = { (controller.mostVisiblePageIndex() ?: 0) < controller.document.pages.lastIndex }
    ) { controller.showNextPage() }

    val previousPage = Command(
        "previous-page", label(Res.string.command_previous_page), CommandGroup.VIEW, section = 1,
        shortcuts = listOf(Shortcut(Key.PageUp)),
        isEnabled = { (controller.mostVisiblePageIndex() ?: 0) > 0 }
    ) { controller.showPreviousPage() }

    val pageGrid = Command(
        "page-grid", label(Res.string.draw_toolbar_action_grid_view), CommandGroup.VIEW, section = 2,
        shortcuts = listOf(primary(Key.G)),
        isChecked = { controller.isPageGridVisible }
    ) { controller.togglePageGrid() }

    val stylusOnly = Command(
        "stylus-only", label(Res.string.draw_menu_stylus_only), CommandGroup.VIEW, section = 2,
        isChecked = { controller.isStylusOnlyMode }
    ) { controller.updateStylusOnlyMode(!controller.isStylusOnlyMode) }

    /** Whether the boxes snap to what is around them (⌥ / Alt held moves freely). */
    val snapping = Command(
        "snapping", label(Res.string.command_snapping), CommandGroup.VIEW, section = 2,
        isChecked = { controller.snapManager.isEnabled }
    ) { controller.snapManager.updateEnabled(!controller.snapManager.isEnabled) }

    // ----- Insert -----

    val addPage = Command(
        "add-page", label(Res.string.context_menu_action_add_page), CommandGroup.INSERT,
        isEnabled = writable
    ) {
        val pages = controller.pageManager
        pages.addNewPageAfter(controller.mostVisiblePageIndex() ?: (controller.document.pages.size - 1))
    }

    val importPdf = Command(
        "import-pdf", label(Res.string.draw_toolbar_action_import_pdf), CommandGroup.INSERT, section = 1,
        shortcuts = listOf(Shortcut(Key.P, primary = true, shift = true)),
        isEnabled = { writable() && controller.tileManager.tileRenderer.pdfAdapter != null }
    ) { actions.importPdf() }

    val importImage = Command(
        "import-image", label(Res.string.draw_toolbar_action_import_image), CommandGroup.INSERT, section = 1,
        shortcuts = listOf(Shortcut(Key.I, primary = true, shift = true)),
        isEnabled = writable
    ) { actions.importImage() }

    // ----- Format -----

    val documentBackground = Command(
        "document-background", label(Res.string.draw_menu_document_background), CommandGroup.FORMAT,
        isEnabled = writable
    ) { actions.editDocumentBackground() }

    // ----- Tools -----

    val drawingMode = Command(
        "drawing-mode", label(Res.string.draw_toolbar_action_draw), CommandGroup.TOOLS,
        isEnabled = writable,
        isChecked = { controller.isDrawingMode }
    ) { controller.toggleDrawingMode() }

    val pen = tool(Tool.INK_PEN, Res.string.draw_toolbar_action_ink_pen, Res.drawable.icon_ink_pen, Key.P)
    val highlighter = tool(Tool.INK_HIGHLIGHTER, Res.string.draw_toolbar_action_highlighter, Res.drawable.icon_ink_highlighter, Key.H)
    val textMarkup = tool(
        Tool.TEXT_MARKUP, Res.string.draw_toolbar_action_text_markup, Res.drawable.icon_text_markup, Key.U,
        // The text of the PDF pages, where the PDFs are drawn
        available = { controller.tileManager.tileRenderer.pdfAdapter != null && controller.document.pages.any { it.pdfs.isNotEmpty() } }
    )
    val eraser = tool(Tool.ERASER, Res.string.draw_toolbar_action_eraser, Res.drawable.icon_ink_eraser, Key.E)
    val text = tool(Tool.TEXT, Res.string.draw_toolbar_action_text, Res.drawable.icon_text_fields, Key.T)
    val lasso = tool(Tool.LAZO, Res.string.draw_toolbar_action_lasso, Res.drawable.icon_lasso_select, Key.L)
    val pan = tool(Tool.PAN, Res.string.draw_toolbar_action_pan, Res.drawable.icon_pan_tool, key = null)

    /** Back to the tool chosen before (also the double tap of an Apple Pencil). */
    val previousTool = Command(
        "previous-tool", label(Res.string.command_previous_tool), CommandGroup.TOOLS, section = 2,
        isEnabled = { writable() && !controller.textEditorManager.isEditing }
    ) {
        if (!controller.isDrawingMode) controller.toggleDrawingMode()
        controller.selectTool(controller.toolManager.previousTool)
    }

    /** Asks the bar of the tools to open the settings of the tool chosen (counted, read by the bar). */
    var paletteRequests by mutableIntStateOf(0)
        private set

    /**
     * Does what a gesture of the stylus itself asks (the double tap or the squeeze of an Apple
     * Pencil, as the user set them in the system).
     */
    fun perform(action: PencilAction) {
        if (controller.isReadOnly) return
        when (action) {
            PencilAction.SWITCH_ERASER -> if (controller.selectedTool == Tool.ERASER) previousTool.performIfEnabled() else eraser.performIfEnabled()
            PencilAction.SWITCH_PREVIOUS -> previousTool.performIfEnabled()
            PencilAction.SHOW_PALETTE -> if (controller.isDrawingMode) paletteRequests++
            PencilAction.NONE -> Unit
        }
    }

    // ----- Format -----

    /** The texts the formatting applies to: the text being written or the texts selected. */
    private val textTarget get() = controller.textFormatTarget?.takeIf { controller.isDrawingMode && writable() }

    val bold = characterFormat(Format.BOLD, Res.string.text_editor_action_bold, Icons.Default.FormatBold, primary(Key.B))
    val italic = characterFormat(Format.ITALIC, Res.string.text_editor_action_italic, Icons.Default.FormatItalic, primary(Key.I))
    val strikethrough = characterFormat(
        Format.STRIKETHROUGH, Res.string.text_editor_action_strikethrough, Icons.Default.FormatStrikethrough, Shortcut(Key.X, primary = true, shift = true)
    )
    val code = characterFormat(Format.CODE, Res.string.text_editor_action_code, Icons.Default.Code, primary(Key.E))
    val formula = characterFormat(Format.FORMULA, Res.string.text_editor_action_math, Icons.Default.Functions, Shortcut(Key.M, primary = true, shift = true))

    val heading = Command(
        "format-heading", label(Res.string.text_editor_action_heading), CommandGroup.FORMAT,
        icon = { rememberVectorPainter(Icons.Default.Title) },
        shortcuts = listOf(Shortcut(Key.One, primary = true, alt = true)),
        isEnabled = { textTarget?.formatsCharacters == true }
    ) { textTarget?.toggleHeading() }

    val alignStart = alignment(TextAlignment.START, Res.string.text_align_start, Icons.AutoMirrored.Filled.FormatAlignLeft, Key.L)
    val alignCenter = alignment(TextAlignment.CENTER, Res.string.text_align_center, Icons.Default.FormatAlignCenter, Key.C)
    val alignEnd = alignment(TextAlignment.END, Res.string.text_align_end, Icons.AutoMirrored.Filled.FormatAlignRight, Key.R)

    val largerText = Command(
        "format-larger", label(Res.string.draw_toolbar_action_increase_font_size), CommandGroup.FORMAT, section = 2,
        shortcuts = listOf(Shortcut(Key.Period, primary = true, shift = true)),
        isEnabled = { textTarget.let { it != null && it.fontSize < TextSizes.MAX } }
    ) { textTarget?.let { it.changeFontSize(TextSizes.larger(it.fontSize)) } }

    val smallerText = Command(
        "format-smaller", label(Res.string.draw_toolbar_action_decrease_font_size), CommandGroup.FORMAT, section = 2,
        shortcuts = listOf(Shortcut(Key.Comma, primary = true, shift = true)),
        isEnabled = { textTarget.let { it != null && it.fontSize > TextSizes.MIN } }
    ) { textTarget?.let { it.changeFontSize(TextSizes.smaller(it.fontSize)) } }

    /** The commands of the formatting bars and of the menu Format. */
    val formats: List<Command> = listOf(bold, italic, strikethrough, code, formula, heading, alignStart, alignCenter, alignEnd, largerText, smallerText)

    /** The command adding or removing [format] around the selection of the text being written. */
    private fun characterFormat(format: Format, title: StringResource, icon: ImageVector, shortcut: Shortcut) = Command(
        id = "format-${format.name.lowercase()}",
        label = label(title),
        group = CommandGroup.FORMAT,
        icon = { rememberVectorPainter(icon) },
        shortcuts = listOf(shortcut),
        isEnabled = { textTarget?.formatsCharacters == true }
    ) { textTarget?.toggle(format) }

    /** The command aligning the lines of the texts at [alignment], ⌘⇧ + [key]. */
    private fun alignment(alignment: TextAlignment, title: StringResource, icon: ImageVector, key: Key) = Command(
        id = "align-${alignment.name.lowercase()}",
        label = label(title),
        group = CommandGroup.FORMAT,
        section = 1,
        icon = { rememberVectorPainter(icon) },
        shortcuts = listOf(Shortcut(key, primary = true, shift = true)),
        isEnabled = { textTarget != null },
        isChecked = { textTarget?.alignment == alignment }
    ) { textTarget?.changeAlignment(alignment) }

    // ----- Help -----

    val about = Command("about", label(Res.string.about_dialog_title), CommandGroup.HELP) { actions.showAbout() }

    /** Every command, in the order of the menus. */
    val all: List<Command> = listOf(
        close,
        undo, redo, cut, copy, paste, delete,
        zoomIn, zoomOut, fitWidth, previousPage, nextPage, pageGrid, stylusOnly, snapping,
        addPage, importPdf, importImage,
        documentBackground,
        drawingMode, pen, highlighter, textMarkup, eraser, text, lasso, pan, previousTool,
        about
    ) + formats

    /**
     * The command choosing [tool] (in drawing mode): its single key [key] is left to the text being
     * written.
     */
    private fun tool(
        tool: Tool,
        title: StringResource,
        icon: DrawableResource,
        key: Key?,
        available: () -> Boolean = { true }
    ) = Command(
        id = "tool-${tool.name.lowercase()}",
        label = label(title),
        group = CommandGroup.TOOLS,
        section = 1,
        icon = { painterResource(icon) },
        shortcuts = listOfNotNull(key?.let { Shortcut(it) }),
        isEnabled = { writable() && !controller.textEditorManager.isEditing && available() },
        isChecked = { controller.isDrawingMode && controller.selectedTool == tool }
    ) {
        if (!controller.isDrawingMode) controller.toggleDrawingMode()
        controller.selectTool(tool)
    }
}

private fun primary(key: Key) = Shortcut(key, primary = true)

private fun label(resource: StringResource): @Composable () -> String = { stringResource(resource) }

/**
 * The commands of the editor of [controller], running the actions of the screen given here (the
 * last ones given, the screen recomposing).
 */
@Composable
internal fun rememberEditorCommands(
    controller: DrawViewController,
    onClose: () -> Unit,
    onImportPdf: () -> Unit,
    onImportImage: () -> Unit,
    onEditDocumentBackground: () -> Unit,
    onShowAbout: () -> Unit
): EditorCommands {
    val clipboard = LocalClipboardManager.current
    val close by rememberUpdatedState(onClose)
    val importPdf by rememberUpdatedState(onImportPdf)
    val importImage by rememberUpdatedState(onImportImage)
    val editDocumentBackground by rememberUpdatedState(onEditDocumentBackground)
    val showAbout by rememberUpdatedState(onShowAbout)
    return remember(controller, clipboard) {
        EditorCommands(
            controller = controller,
            clipboard = clipboard,
            actions = EditorScreenActions(
                close = { close() },
                importPdf = { importPdf() },
                importImage = { importImage() },
                editDocumentBackground = { editDocumentBackground() },
                showAbout = { showAbout() }
            )
        )
    }
}
