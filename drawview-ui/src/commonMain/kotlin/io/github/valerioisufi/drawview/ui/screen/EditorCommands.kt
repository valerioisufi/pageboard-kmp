package io.github.valerioisufi.drawview.ui.screen

import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.canvas.text.RichTextEditing.Format
import io.github.valerioisufi.drawview.canvas.controller.SelectionManager
import io.github.valerioisufi.drawview.canvas.tool.Tool
import io.github.valerioisufi.drawview.model.change.StackMove
import io.github.valerioisufi.drawview.model.TextAlignment
import io.github.valerioisufi.drawview.model.text.TextSizes
import io.github.valerioisufi.drawview.ui.command.Command
import io.github.valerioisufi.drawview.ui.command.CommandGroup
import io.github.valerioisufi.drawview.ui.command.Shortcut
import io.github.valerioisufi.drawview.ui.generated.resources.Res
import io.github.valerioisufi.drawview.ui.generated.resources.about_dialog_title
import io.github.valerioisufi.drawview.ui.generated.resources.command_bring_forward
import io.github.valerioisufi.drawview.ui.generated.resources.command_bring_to_front
import io.github.valerioisufi.drawview.ui.generated.resources.command_close
import io.github.valerioisufi.drawview.ui.generated.resources.command_fit_width
import io.github.valerioisufi.drawview.ui.generated.resources.command_next_page
import io.github.valerioisufi.drawview.ui.generated.resources.command_previous_page
import io.github.valerioisufi.drawview.ui.generated.resources.command_previous_tool
import io.github.valerioisufi.drawview.ui.generated.resources.command_send_backward
import io.github.valerioisufi.drawview.ui.generated.resources.command_send_to_back
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
import io.github.valerioisufi.drawview.ui.generated.resources.text_align_center
import io.github.valerioisufi.drawview.ui.generated.resources.text_align_end
import io.github.valerioisufi.drawview.ui.generated.resources.text_align_start
import io.github.valerioisufi.drawview.ui.generated.resources.text_editor_action_bold
import io.github.valerioisufi.drawview.ui.generated.resources.text_editor_action_bullet_list
import io.github.valerioisufi.drawview.ui.generated.resources.text_editor_action_code
import io.github.valerioisufi.drawview.ui.generated.resources.text_editor_action_code_block
import io.github.valerioisufi.drawview.ui.generated.resources.text_editor_action_heading
import io.github.valerioisufi.drawview.ui.generated.resources.text_editor_action_italic
import io.github.valerioisufi.drawview.ui.generated.resources.text_editor_action_line_numbers
import io.github.valerioisufi.drawview.ui.generated.resources.text_editor_action_math
import io.github.valerioisufi.drawview.ui.generated.resources.text_editor_action_numbered_list
import io.github.valerioisufi.drawview.ui.generated.resources.text_editor_action_strikethrough
import io.github.valerioisufi.drawview.ui.platform.PencilAction
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import org.jetbrains.compose.resources.StringResource
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
internal class EditorCommands internal constructor(
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
        icon = { rememberVectorPainter(DrawViewIcons.Undo) },
        shortcuts = listOf(primary(Key.Z)),
        isEnabled = { controller.canUndo }
    ) { controller.undo() }

    val redo = Command(
        "redo", label(Res.string.common_action_redo), CommandGroup.EDIT,
        icon = { rememberVectorPainter(DrawViewIcons.Redo) },
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
        icon = { rememberVectorPainter(DrawViewIcons.Scissors) },
        shortcuts = listOf(primary(Key.X)),
        isEnabled = { hasSelection && writable() }
    ) {
        shareSelectedText()
        controller.selectionManager.cutSelection()
    }

    val copy = Command(
        "copy", label(Res.string.common_action_copy), CommandGroup.EDIT, section = 1,
        icon = { rememberVectorPainter(DrawViewIcons.Copy) },
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
        icon = { rememberVectorPainter(DrawViewIcons.Clipboard) },
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
        icon = { rememberVectorPainter(DrawViewIcons.Trash) },
        destructive = true,
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
        icon = { rememberVectorPainter(DrawViewIcons.ZoomIn) },
        // ⌘+ is ⌘= (with or without ⇧) on many keyboards, + on others and on the keypad
        shortcuts = listOf(primary(Key.Plus), primary(Key.Equals), Shortcut(Key.Equals, primary = true, shift = true), primary(Key.NumPadAdd))
    ) { controller.viewport.zoomIn() }

    val zoomOut = Command(
        "zoom-out", label(Res.string.command_zoom_out), CommandGroup.VIEW,
        shortcuts = listOf(primary(Key.Minus), primary(Key.NumPadSubtract))
    ) { controller.viewport.zoomOut() }

    val fitWidth = Command(
        "fit-width", label(Res.string.command_fit_width), CommandGroup.VIEW,
        icon = { rememberVectorPainter(DrawViewIcons.MoveH) },
        shortcuts = listOf(primary(Key.Zero))
    ) { controller.viewport.fitWidth() }

    val nextPage = Command(
        "next-page", label(Res.string.command_next_page), CommandGroup.VIEW, section = 1,
        shortcuts = listOf(Shortcut(Key.PageDown)),
        isEnabled = { (controller.viewport.mostVisiblePageIndex() ?: 0) < controller.document.pages.lastIndex }
    ) { controller.viewport.showNextPage() }

    val previousPage = Command(
        "previous-page", label(Res.string.command_previous_page), CommandGroup.VIEW, section = 1,
        shortcuts = listOf(Shortcut(Key.PageUp)),
        isEnabled = { (controller.viewport.mostVisiblePageIndex() ?: 0) > 0 }
    ) { controller.viewport.showPreviousPage() }

    val pageGrid = Command(
        "page-grid", label(Res.string.draw_toolbar_action_grid_view), CommandGroup.VIEW, section = 2,
        icon = { rememberVectorPainter(DrawViewIcons.LayoutGrid) },
        shortcuts = listOf(primary(Key.G)),
        isChecked = { controller.isPageGridVisible }
    ) { controller.togglePageGrid() }

    val stylusOnly = Command(
        "stylus-only", label(Res.string.draw_menu_stylus_only), CommandGroup.VIEW, section = 2,
        icon = { rememberVectorPainter(DrawViewIcons.Pencil) },
        isChecked = { controller.isStylusOnlyMode }
    ) { controller.updateStylusOnlyMode(!controller.isStylusOnlyMode) }

    /** Whether the boxes snap to what is around them (⌥ / Alt held moves freely). */
    val snapping = Command(
        "snapping", label(Res.string.command_snapping), CommandGroup.VIEW, section = 2,
        icon = { rememberVectorPainter(DrawViewIcons.Magnet) },
        isChecked = { controller.snapManager.isEnabled }
    ) { controller.snapManager.updateEnabled(!controller.snapManager.isEnabled) }

    // ----- Insert -----

    val addPage = Command(
        "add-page", label(Res.string.context_menu_action_add_page), CommandGroup.INSERT,
        icon = { rememberVectorPainter(DrawViewIcons.FilePlus2) },
        isEnabled = writable
    ) {
        val pages = controller.pageManager
        pages.addNewPageAfter(controller.viewport.mostVisiblePageIndex() ?: (controller.document.pages.size - 1))
    }

    val importPdf = Command(
        "import-pdf", label(Res.string.draw_toolbar_action_import_pdf), CommandGroup.INSERT, section = 1,
        icon = { rememberVectorPainter(DrawViewIcons.FileText) },
        shortcuts = listOf(Shortcut(Key.P, primary = true, shift = true)),
        isEnabled = { writable() && controller.tileManager.tileRenderer.pdfAdapter != null }
    ) { actions.importPdf() }

    val importImage = Command(
        "import-image", label(Res.string.draw_toolbar_action_import_image), CommandGroup.INSERT, section = 1,
        icon = { rememberVectorPainter(DrawViewIcons.Image) },
        shortcuts = listOf(Shortcut(Key.I, primary = true, shift = true)),
        isEnabled = writable
    ) { actions.importImage() }

    // ----- Format -----

    val documentBackground = Command(
        "document-background", label(Res.string.draw_menu_document_background), CommandGroup.FORMAT,
        icon = { rememberVectorPainter(DrawViewIcons.Grid) },
        isEnabled = writable
    ) { actions.editDocumentBackground() }

    // ----- Arrange -----

    /**
     * The commands moving the selection in the stack of its page, with the shortcuts of Keynote and
     * Pages (letters: the same keys on every layout); the brackets of Figma run them too.
     */
    val bringToFront = arrangement(
        StackMove.FRONT, Res.string.command_bring_to_front, DrawViewIcons.ArrowUpToLine,
        Shortcut(Key.F, primary = true, shift = true), Shortcut(Key.RightBracket, primary = true, alt = true)
    )
    val bringForward = arrangement(
        StackMove.FORWARD, Res.string.command_bring_forward, DrawViewIcons.ChevronUp,
        Shortcut(Key.F, primary = true, shift = true, alt = true), primary(Key.RightBracket)
    )
    val sendBackward = arrangement(
        StackMove.BACKWARD, Res.string.command_send_backward, DrawViewIcons.ChevronDown,
        Shortcut(Key.B, primary = true, shift = true, alt = true), primary(Key.LeftBracket)
    )
    val sendToBack = arrangement(
        StackMove.BACK, Res.string.command_send_to_back, DrawViewIcons.ArrowDownToLine,
        Shortcut(Key.B, primary = true, shift = true), Shortcut(Key.LeftBracket, primary = true, alt = true)
    )

    /** The commands of the menu Arrange, of the selection bar and of the context menu of a selection. */
    val arrangements: List<Command> = listOf(bringToFront, bringForward, sendBackward, sendToBack)

    /** The command moving the selection by [move] in the stack of its page (see [SelectionManager.arrange]). */
    private fun arrangement(move: StackMove, title: StringResource, icon: ImageVector, vararg shortcuts: Shortcut) = Command(
        id = "arrange-${move.name.lowercase()}",
        label = label(title),
        group = CommandGroup.ARRANGE,
        icon = { rememberVectorPainter(icon) },
        shortcuts = shortcuts.toList(),
        isEnabled = { hasSelection && writable() && controller.selectionManager.canArrange(move) }
    ) { controller.selectionManager.arrange(move) }

    // ----- Tools -----

    val drawingMode = Command(
        "drawing-mode", label(Res.string.draw_toolbar_action_draw), CommandGroup.TOOLS,
        icon = { rememberVectorPainter(DrawViewIcons.Pen) },
        isEnabled = writable,
        isChecked = { controller.isDrawingMode }
    ) { controller.toggleDrawingMode() }

    val pen = tool(Tool.INK_PEN, Res.string.draw_toolbar_action_ink_pen, DrawViewIcons.Pen, Key.P)
    val highlighter = tool(Tool.INK_HIGHLIGHTER, Res.string.draw_toolbar_action_highlighter, DrawViewIcons.Highlighter, Key.H)
    val textMarkup = tool(
        Tool.TEXT_MARKUP, Res.string.draw_toolbar_action_text_markup, DrawViewIcons.TextMarkup, Key.U,
        // The text of the PDF pages, where the PDFs are drawn
        available = { controller.tileManager.tileRenderer.pdfAdapter != null && controller.document.pages.any { it.pdfs.isNotEmpty() } }
    )
    val eraser = tool(Tool.ERASER, Res.string.draw_toolbar_action_eraser, DrawViewIcons.Eraser, Key.E)
    val text = tool(Tool.TEXT, Res.string.draw_toolbar_action_text, DrawViewIcons.Text, Key.T)
    val lasso = tool(Tool.LAZO, Res.string.draw_toolbar_action_lasso, DrawViewIcons.Lasso, Key.L)
    val pan = tool(Tool.PAN, Res.string.draw_toolbar_action_pan, DrawViewIcons.Pan, key = null)

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

    /** Whether the formats of the characters apply: to the text being written, outside its blocks of code. */
    private val formatsText get() = textTarget.let { it != null && it.formatsCharacters && !it.inCodeBlock }

    val bold = characterFormat(Format.BOLD, Res.string.text_editor_action_bold, DrawViewIcons.Bold, primary(Key.B))
    val italic = characterFormat(Format.ITALIC, Res.string.text_editor_action_italic, DrawViewIcons.Italic, primary(Key.I))
    val strikethrough = characterFormat(
        Format.STRIKETHROUGH, Res.string.text_editor_action_strikethrough, DrawViewIcons.Strikethrough, Shortcut(Key.X, primary = true, shift = true)
    )
    val code = characterFormat(Format.CODE, Res.string.text_editor_action_code, DrawViewIcons.Code, primary(Key.E))

    val codeBlock = Command(
        "format-code-block", label(Res.string.text_editor_action_code_block), CommandGroup.FORMAT,
        icon = { rememberVectorPainter(DrawViewIcons.SquareCode) },
        shortcuts = listOf(Shortcut(Key.C, primary = true, alt = true)),
        isEnabled = { textTarget?.formatsCharacters == true }
    ) { textTarget?.toggleCodeBlock() }

    val lineNumbers = Command(
        "format-line-numbers", label(Res.string.text_editor_action_line_numbers), CommandGroup.FORMAT,
        icon = { rememberVectorPainter(DrawViewIcons.Hash) },
        shortcuts = listOf(Shortcut(Key.L, primary = true, alt = true)),
        isEnabled = { textTarget?.inCodeBlock == true },
        isChecked = { textTarget?.codeLineNumbers == true }
    ) { textTarget?.toggleLineNumbers() }
    val formula = characterFormat(Format.FORMULA, Res.string.text_editor_action_math, DrawViewIcons.Sigma, Shortcut(Key.M, primary = true, shift = true))

    val heading = Command(
        "format-heading", label(Res.string.text_editor_action_heading), CommandGroup.FORMAT,
        icon = { rememberVectorPainter(DrawViewIcons.Heading) },
        shortcuts = listOf(Shortcut(Key.One, primary = true, alt = true)),
        isEnabled = { formatsText }
    ) { textTarget?.toggleHeading() }

    val bulletList = Command(
        "format-bullet-list", label(Res.string.text_editor_action_bullet_list), CommandGroup.FORMAT,
        icon = { rememberVectorPainter(DrawViewIcons.List) },
        shortcuts = listOf(Shortcut(Key.Eight, primary = true, shift = true)),
        isEnabled = { formatsText }
    ) { textTarget?.toggleBulletList() }

    val numberedList = Command(
        "format-numbered-list", label(Res.string.text_editor_action_numbered_list), CommandGroup.FORMAT,
        icon = { rememberVectorPainter(DrawViewIcons.ListOrdered) },
        shortcuts = listOf(Shortcut(Key.Seven, primary = true, shift = true)),
        isEnabled = { formatsText }
    ) { textTarget?.toggleNumberedList() }

    val alignStart = alignment(TextAlignment.START, Res.string.text_align_start, DrawViewIcons.AlignLeft, Key.L)
    val alignCenter = alignment(TextAlignment.CENTER, Res.string.text_align_center, DrawViewIcons.AlignCenter, Key.C)
    val alignEnd = alignment(TextAlignment.END, Res.string.text_align_end, DrawViewIcons.AlignRight, Key.R)

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
    val formats: List<Command> = listOf(
        bold, italic, strikethrough, code, codeBlock, lineNumbers, heading, bulletList, numberedList, formula,
        alignStart, alignCenter, alignEnd, largerText, smallerText
    )

    /** The command adding or removing [format] around the selection of the text being written. */
    private fun characterFormat(format: Format, title: StringResource, icon: ImageVector, shortcut: Shortcut) = Command(
        id = "format-${format.name.lowercase()}",
        label = label(title),
        group = CommandGroup.FORMAT,
        icon = { rememberVectorPainter(icon) },
        shortcuts = listOf(shortcut),
        isEnabled = { formatsText }
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

    val about = Command("about", label(Res.string.about_dialog_title), CommandGroup.HELP, icon = { rememberVectorPainter(DrawViewIcons.Info) }) { actions.showAbout() }

    /** Every command, in the order of the menus. */
    val all: List<Command> = listOf(
        close,
        undo, redo, cut, copy, paste, delete,
        zoomIn, zoomOut, fitWidth, previousPage, nextPage, pageGrid, stylusOnly, snapping,
        addPage, importPdf, importImage,
        documentBackground,
        bringToFront, bringForward, sendBackward, sendToBack,
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
        icon: ImageVector,
        key: Key?,
        available: () -> Boolean = { true }
    ) = Command(
        id = "tool-${tool.name.lowercase()}",
        label = label(title),
        group = CommandGroup.TOOLS,
        section = 1,
        icon = { rememberVectorPainter(icon) },
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
