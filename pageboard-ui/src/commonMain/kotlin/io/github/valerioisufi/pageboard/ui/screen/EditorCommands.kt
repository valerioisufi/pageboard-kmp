package io.github.valerioisufi.pageboard.ui.screen

import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import io.github.valerioisufi.pageboard.canvas.controller.PageboardController
import io.github.valerioisufi.pageboard.canvas.controller.EditorClipboard
import io.github.valerioisufi.pageboard.canvas.controller.PageManager
import io.github.valerioisufi.pageboard.canvas.controller.SelectionManager
import io.github.valerioisufi.pageboard.canvas.text.RichTextEditing.Format
import io.github.valerioisufi.pageboard.canvas.text.TableEditing
import io.github.valerioisufi.pageboard.canvas.tool.Tool
import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.model.TextAlignment
import io.github.valerioisufi.pageboard.model.change.CopiedElements
import io.github.valerioisufi.pageboard.model.change.CopiedPages
import io.github.valerioisufi.pageboard.model.change.StackMove
import io.github.valerioisufi.pageboard.model.text.TextSizes
import io.github.valerioisufi.pageboard.ui.command.Command
import io.github.valerioisufi.pageboard.ui.command.CommandGroup
import io.github.valerioisufi.pageboard.ui.command.Shortcut
import io.github.valerioisufi.pageboard.ui.editor.rememberClipboardText
import io.github.valerioisufi.pageboard.ui.extension.LocalTextSuggestions
import io.github.valerioisufi.pageboard.ui.generated.resources.Res
import io.github.valerioisufi.pageboard.ui.generated.resources.about_dialog_title
import io.github.valerioisufi.pageboard.ui.generated.resources.command_bring_forward
import io.github.valerioisufi.pageboard.ui.generated.resources.command_bring_to_front
import io.github.valerioisufi.pageboard.ui.generated.resources.command_close
import io.github.valerioisufi.pageboard.ui.generated.resources.command_fit_width
import io.github.valerioisufi.pageboard.ui.generated.resources.command_insert_shape
import io.github.valerioisufi.pageboard.ui.generated.resources.command_layers
import io.github.valerioisufi.pageboard.ui.generated.resources.command_lock_selection
import io.github.valerioisufi.pageboard.ui.generated.resources.command_next_page
import io.github.valerioisufi.pageboard.ui.generated.resources.command_previous_page
import io.github.valerioisufi.pageboard.ui.generated.resources.command_previous_tool
import io.github.valerioisufi.pageboard.ui.generated.resources.command_protractor
import io.github.valerioisufi.pageboard.ui.generated.resources.command_quick_pan
import io.github.valerioisufi.pageboard.ui.generated.resources.command_rename
import io.github.valerioisufi.pageboard.ui.generated.resources.command_ruler
import io.github.valerioisufi.pageboard.ui.generated.resources.command_scroll_axis_lock
import io.github.valerioisufi.pageboard.ui.generated.resources.command_send_backward
import io.github.valerioisufi.pageboard.ui.generated.resources.command_send_to_back
import io.github.valerioisufi.pageboard.ui.generated.resources.command_shapes
import io.github.valerioisufi.pageboard.ui.generated.resources.command_snapping
import io.github.valerioisufi.pageboard.ui.generated.resources.command_text_suggestions
import io.github.valerioisufi.pageboard.ui.generated.resources.command_zoom_in
import io.github.valerioisufi.pageboard.ui.generated.resources.command_zoom_out
import io.github.valerioisufi.pageboard.ui.generated.resources.common_action_copy
import io.github.valerioisufi.pageboard.ui.generated.resources.common_action_cut
import io.github.valerioisufi.pageboard.ui.generated.resources.common_action_delete
import io.github.valerioisufi.pageboard.ui.generated.resources.common_action_paste
import io.github.valerioisufi.pageboard.ui.generated.resources.common_action_paste_plain
import io.github.valerioisufi.pageboard.ui.generated.resources.common_action_redo
import io.github.valerioisufi.pageboard.ui.generated.resources.common_action_undo
import io.github.valerioisufi.pageboard.ui.generated.resources.context_menu_action_add_page
import io.github.valerioisufi.pageboard.ui.generated.resources.draw_menu_document_background
import io.github.valerioisufi.pageboard.ui.generated.resources.draw_menu_stylus_only
import io.github.valerioisufi.pageboard.ui.generated.resources.draw_toolbar_action_decrease_font_size
import io.github.valerioisufi.pageboard.ui.generated.resources.draw_toolbar_action_draw
import io.github.valerioisufi.pageboard.ui.generated.resources.draw_toolbar_action_eraser
import io.github.valerioisufi.pageboard.ui.generated.resources.draw_toolbar_action_grid_view
import io.github.valerioisufi.pageboard.ui.generated.resources.draw_toolbar_action_highlighter
import io.github.valerioisufi.pageboard.ui.generated.resources.draw_toolbar_action_import_image
import io.github.valerioisufi.pageboard.ui.generated.resources.draw_toolbar_action_import_pdf
import io.github.valerioisufi.pageboard.ui.generated.resources.draw_toolbar_action_import_text
import io.github.valerioisufi.pageboard.ui.generated.resources.draw_toolbar_action_increase_font_size
import io.github.valerioisufi.pageboard.ui.generated.resources.draw_toolbar_action_ink_pen
import io.github.valerioisufi.pageboard.ui.generated.resources.draw_toolbar_action_lasso
import io.github.valerioisufi.pageboard.ui.generated.resources.draw_toolbar_action_pan
import io.github.valerioisufi.pageboard.ui.generated.resources.draw_toolbar_action_shapes
import io.github.valerioisufi.pageboard.ui.generated.resources.draw_toolbar_action_text
import io.github.valerioisufi.pageboard.ui.generated.resources.draw_toolbar_action_text_markup
import io.github.valerioisufi.pageboard.ui.generated.resources.table_align_center
import io.github.valerioisufi.pageboard.ui.generated.resources.table_align_end
import io.github.valerioisufi.pageboard.ui.generated.resources.table_align_start
import io.github.valerioisufi.pageboard.ui.generated.resources.table_column_left
import io.github.valerioisufi.pageboard.ui.generated.resources.table_column_right
import io.github.valerioisufi.pageboard.ui.generated.resources.table_delete
import io.github.valerioisufi.pageboard.ui.generated.resources.table_delete_column
import io.github.valerioisufi.pageboard.ui.generated.resources.table_delete_row
import io.github.valerioisufi.pageboard.ui.generated.resources.table_row_above
import io.github.valerioisufi.pageboard.ui.generated.resources.table_row_below
import io.github.valerioisufi.pageboard.ui.generated.resources.text_align_center
import io.github.valerioisufi.pageboard.ui.generated.resources.text_align_end
import io.github.valerioisufi.pageboard.ui.generated.resources.text_align_start
import io.github.valerioisufi.pageboard.ui.generated.resources.text_editor_action_bold
import io.github.valerioisufi.pageboard.ui.generated.resources.text_editor_action_bullet_list
import io.github.valerioisufi.pageboard.ui.generated.resources.text_editor_action_code
import io.github.valerioisufi.pageboard.ui.generated.resources.text_editor_action_code_block
import io.github.valerioisufi.pageboard.ui.generated.resources.text_editor_action_heading
import io.github.valerioisufi.pageboard.ui.generated.resources.text_editor_action_check_item
import io.github.valerioisufi.pageboard.ui.generated.resources.text_editor_action_checklist
import io.github.valerioisufi.pageboard.ui.generated.resources.text_editor_action_heading_level
import io.github.valerioisufi.pageboard.ui.generated.resources.text_editor_action_heading_n
import io.github.valerioisufi.pageboard.ui.generated.resources.text_editor_action_normal_text
import io.github.valerioisufi.pageboard.ui.generated.resources.command_find
import io.github.valerioisufi.pageboard.ui.generated.resources.text_editor_action_italic
import io.github.valerioisufi.pageboard.ui.generated.resources.text_editor_action_line_numbers
import io.github.valerioisufi.pageboard.ui.generated.resources.text_editor_action_math
import io.github.valerioisufi.pageboard.ui.generated.resources.text_editor_action_numbered_list
import io.github.valerioisufi.pageboard.ui.generated.resources.text_editor_action_numbering
import io.github.valerioisufi.pageboard.ui.generated.resources.text_editor_action_bullets
import io.github.valerioisufi.pageboard.ui.generated.resources.text_editor_action_numbered_checklist
import io.github.valerioisufi.pageboard.ui.generated.resources.text_editor_action_move_completed
import io.github.valerioisufi.pageboard.ui.generated.resources.text_editor_action_checklist_options
import io.github.valerioisufi.pageboard.ui.generated.resources.text_editor_action_quote
import io.github.valerioisufi.pageboard.ui.generated.resources.text_editor_action_strikethrough
import io.github.valerioisufi.pageboard.ui.generated.resources.text_editor_action_table
import io.github.valerioisufi.pageboard.ui.platform.PencilAction
import io.github.valerioisufi.pageboard.ui.theme.PageboardIcons
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
    val importText: () -> Unit,
    val editDocumentBackground: () -> Unit,
    val showAbout: () -> Unit,
    val canRename: () -> Boolean = { false },
    val isLayersOpen: () -> Boolean = { false },
    val toggleLayers: () -> Unit = {}
)

/**
 * The commands of the editor of [controller]: the toolbar, the menu bar, the shortcuts and the
 * tooltips show the same ones. Each asks its expert whether it can run (the history, the
 * selections, the mode of the editor) and runs through it.
 */
@Stable
internal class EditorCommands internal constructor(
    private val controller: PageboardController,
    private val clipboard: ClipboardManager,
    private val actions: EditorScreenActions,
    private val formattedText: () -> String? = { null },
    hasTextSuggestions: Boolean = false
) {
    private val writable = { !controller.isReadOnly }

    // ----- File -----

    val close = Command("close", label(Res.string.command_close), CommandGroup.FILE, shortcuts = listOf(primary(Key.W))) { actions.close() }

    /** Asks the title of the document to open with its name to change (counted, read by the title). */
    var renameRequests by mutableIntStateOf(0)
        private set

    val rename = Command(
        "rename", label(Res.string.command_rename), CommandGroup.FILE,
        icon = { rememberVectorPainter(PageboardIcons.SquarePen) },
        isEnabled = { writable() && actions.canRename() }
    ) { renameRequests++ }

    // ----- Edit -----

    val undo = Command(
        "undo", label(Res.string.common_action_undo), CommandGroup.EDIT,
        icon = { rememberVectorPainter(PageboardIcons.Undo) },
        shortcuts = listOf(primary(Key.Z)),
        isEnabled = { controller.canUndo }
    ) { controller.undo() }

    /** Opens the search in the document (see `DocumentFind`), or puts the focus back in it. */
    val find = Command(
        "find", label(Res.string.command_find), CommandGroup.EDIT, section = 2,
        icon = { rememberVectorPainter(PageboardIcons.Search) },
        shortcuts = listOf(primary(Key.F))
    ) {
        if (controller.find.isOpen) controller.find.close()
        controller.find.open()
    }

    val redo = Command(
        "redo", label(Res.string.common_action_redo), CommandGroup.EDIT,
        icon = { rememberVectorPainter(PageboardIcons.Redo) },
        shortcuts = listOf(Shortcut(Key.Z, primary = true, shift = true), primary(Key.Y)),
        isEnabled = { controller.canRedo }
    ) { controller.redo() }

    /** The lasso selection, if any (the elements of the pages). */
    private val hasSelection get() = controller.isDrawingMode && controller.selectionManager.currentSelection != null

    /** The selected text of the PDFs or the selected mark, if any. */
    private val hasTextSelection get() = controller.textMarkupManager.hasSelection

    /**
     * After a copy into the clipboard of the application ([EditorClipboard]): puts [text] (the
     * text of the texts copied) in the clipboard of the system, for the other applications, and
     * remembers what the system holds then, so that a text copied after in another application
     * wins (see [EditorClipboard.systemText]).
     */
    private fun shareCopied(text: String?) {
        if (text != null) clipboard.setText(AnnotatedString(text))
        controller.clipboard.systemText = clipboard.getText()?.text
    }

    val cut = Command(
        "cut", label(Res.string.common_action_cut), CommandGroup.EDIT, section = 1,
        icon = { rememberVectorPainter(PageboardIcons.Scissors) },
        shortcuts = listOf(primary(Key.X)),
        isEnabled = { hasSelection && writable() }
    ) {
        val text = controller.selectionManager.selectedPlainText
        controller.selectionManager.cutSelection()
        shareCopied(text)
    }

    val copy = Command(
        "copy", label(Res.string.common_action_copy), CommandGroup.EDIT, section = 1,
        icon = { rememberVectorPainter(PageboardIcons.Copy) },
        shortcuts = listOf(primary(Key.C)),
        isEnabled = { hasTextSelection || hasSelection }
    ) {
        val text = controller.textMarkupManager.selectedText
        if (hasTextSelection) {
            if (!text.isNullOrEmpty()) clipboard.setText(AnnotatedString(text))
        } else {
            val copied = controller.selectionManager.selectedPlainText
            controller.selectionManager.copySelection()
            shareCopied(copied)
        }
    }

    val paste = Command(
        "paste", label(Res.string.common_action_paste), CommandGroup.EDIT, section = 1,
        icon = { rememberVectorPainter(PageboardIcons.Clipboard) },
        shortcuts = listOf(primary(Key.V)),
        isEnabled = { controller.isDrawingMode && writable() }
    ) { pasteAt(null) }

    /** A text of another application without its formatting: the same paste, reading its plain text. */
    val pastePlain = Command(
        "pastePlain", label(Res.string.common_action_paste_plain), CommandGroup.EDIT, section = 1,
        icon = { rememberVectorPainter(PageboardIcons.Clipboard) },
        shortcuts = listOf(Shortcut(Key.V, primary = true, shift = true)),
        isEnabled = { controller.isDrawingMode && writable() }
    ) {
        clipboard.getText()?.text?.takeIf { it.isNotBlank() }?.let { controller.textEditorManager.pasteText(it) }
    }

    /**
     * Pastes what was copied last at [position] of the canvas (the page shown when `null`): a text
     * copied in another application becomes a new text; elements copied in any document are put
     * there; pages copied go after the page there.
     */
    fun pasteAt(position: Offset?) {
        val text = clipboard.getText()?.text
        val content = controller.clipboard.content
        if (!text.isNullOrBlank() && isNewerThanCopied(text)) {
            val formatted = formattedText()
            if (formatted != null) {
                controller.textEditorManager.pasteMarkdown(formatted, position)
            } else {
                controller.textEditorManager.pasteText(text, position)
            }
            return
        }
        when (content) {
            is CopiedElements -> controller.selectionManager.pasteSelection(position)
            // At a point: after the page of the context menu opened there
            is CopiedPages -> controller.pageManager.pastePages(
                controller.pageManager.contextMenuTargetPageIndex.takeIf { position != null && it in controller.document.pages.indices }
                    ?: controller.viewport.mostVisiblePageIndex()
                    ?: (controller.document.pages.size - 1)
            )
            null -> Unit
        }
    }

    /** Whether [text] of the clipboard of the system was copied after what was copied in the editors (see [EditorClipboard.systemText]). */
    private fun isNewerThanCopied(text: String): Boolean =
        controller.clipboard.content == null || text != controller.clipboard.systemText

    /** Whether a paste puts pages (copied last), not elements or a text (see [pasteAt]). */
    fun pastesPages(): Boolean {
        if (controller.clipboard.content !is CopiedPages) return false
        val text = clipboard.getText()?.text
        return text.isNullOrBlank() || !isNewerThanCopied(text)
    }

    /** Whether there is something to paste: elements or pages copied in any document, or a text of the clipboard. */
    fun canPaste(): Boolean = controller.clipboard.content != null || clipboard.getText()?.text?.isNotBlank() == true

    /** Copies the page at [pageIndex] with its content (see [PageManager.copyPage]). */
    fun copyPage(pageIndex: Int) {
        controller.pageManager.copyPage(pageIndex)
        shareCopied(null)
    }

    /** Copies the pages [ids] with their content, in the order of the document (see [PageManager.copyPages]). */
    fun copyPages(ids: Collection<String>) {
        controller.pageManager.copyPages(ids)
        shareCopied(null)
    }

    /** Pastes the pages copied after the last of the pages [ids]. */
    fun pastePagesAfter(ids: Collection<String>) {
        if (writable()) controller.pageManager.pastePagesAfter(ids)
    }

    /** Duplicates the pages [ids] with their content after the last of them (see [PageManager.duplicatePages]). */
    fun duplicatePages(ids: Collection<String>) {
        if (writable()) controller.pageManager.duplicatePages(ids)
    }

    /** Whether pages were copied, to be pasted after a page (see [PageManager.pastePages]). */
    fun canPastePages(): Boolean = writable() && controller.pageManager.canPastePages()

    /** Pastes the pages copied after the page at [pageIndex]. */
    fun pastePagesAfter(pageIndex: Int) {
        if (writable()) controller.pageManager.pastePages(pageIndex)
    }

    val delete = Command(
        "delete", label(Res.string.common_action_delete), CommandGroup.EDIT, section = 1,
        icon = { rememberVectorPainter(PageboardIcons.Trash) },
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
        icon = { rememberVectorPainter(PageboardIcons.ZoomIn) },
        // ⌘+ is ⌘= (with or without ⇧) on many keyboards, + on others and on the keypad
        shortcuts = listOf(primary(Key.Plus), primary(Key.Equals), Shortcut(Key.Equals, primary = true, shift = true), primary(Key.NumPadAdd))
    ) { controller.viewport.zoomIn() }

    val zoomOut = Command(
        "zoom-out", label(Res.string.command_zoom_out), CommandGroup.VIEW,
        shortcuts = listOf(primary(Key.Minus), primary(Key.NumPadSubtract))
    ) { controller.viewport.zoomOut() }

    val fitWidth = Command(
        "fit-width", label(Res.string.command_fit_width), CommandGroup.VIEW,
        icon = { rememberVectorPainter(PageboardIcons.MoveH) },
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
        icon = { rememberVectorPainter(PageboardIcons.LayoutGrid) },
        shortcuts = listOf(primary(Key.G)),
        isChecked = { controller.isPageGridVisible }
    ) { controller.togglePageGrid() }

    val stylusOnly = Command(
        "stylus-only", label(Res.string.draw_menu_stylus_only), CommandGroup.VIEW, section = 2,
        icon = { rememberVectorPainter(PageboardIcons.Pencil) },
        isChecked = { controller.isStylusOnlyMode }
    ) { controller.updateStylusOnlyMode(!controller.isStylusOnlyMode) }

    /** Whether the boxes snap to what is around them (⌥ / Alt held moves freely). */
    val snapping = Command(
        "snapping", label(Res.string.command_snapping), CommandGroup.VIEW, section = 2,
        icon = { rememberVectorPainter(PageboardIcons.Magnet) },
        isChecked = { controller.snapManager.isEnabled }
    ) { controller.snapManager.updateEnabled(!controller.snapManager.isEnabled) }

    /** Whether a stroke held still at its end becomes its shape (a line, a circle, a box...). */
    val shapes = Command(
        "shapes", label(Res.string.command_shapes), CommandGroup.VIEW, section = 2,
        icon = { rememberVectorPainter(PageboardIcons.Shapes) },
        isChecked = { controller.shapeSnapper.isEnabled }
    ) { controller.shapeSnapper.updateEnabled(!controller.shapeSnapper.isEnabled) }

    /** Shows or hides the interactive on-canvas straightedge ruler. */
    val ruler = Command(
        "ruler", label(Res.string.command_ruler), CommandGroup.VIEW, section = 2,
        icon = { rememberVectorPainter(PageboardIcons.Ruler) },
        isChecked = { controller.geometryGuides.isRulerVisible }
    ) { controller.geometryGuides.toggleRuler() }

    /** Shows or hides the interactive on-canvas protractor. */
    val protractor = Command(
        "protractor", label(Res.string.command_protractor), CommandGroup.VIEW, section = 2,
        icon = { rememberVectorPainter(PageboardIcons.DraftingCompass) },
        isChecked = { controller.geometryGuides.isProtractorVisible }
    ) { controller.geometryGuides.toggleProtractor() }

    /** Shows or hides the adaptive per-page Layers panel. */
    val layers = Command(
        "layers", label(Res.string.command_layers), CommandGroup.VIEW, section = 2,
        icon = { rememberVectorPainter(PageboardIcons.Layers) },
        isChecked = { actions.isLayersOpen() }
    ) { actions.toggleLayers() }

    /** Locks or unlocks predominant-axis filtering on trackpad and wheel scrolling. */
    val scrollAxisLock = Command(
        "scroll-axis-lock", label(Res.string.command_scroll_axis_lock), CommandGroup.VIEW, section = 2,
        icon = { rememberVectorPainter(PageboardIcons.Lock) },
        isChecked = { controller.isScrollAxisLockEnabled }
    ) { controller.updateScrollAxisLockEnabled(!controller.isScrollAxisLockEnabled) }

    /** Toggles session-scoped one-finger Quick Pan mode (touch pans while stylus still draws). */
    val quickPan = Command(
        "quick-pan", label(Res.string.command_quick_pan), CommandGroup.VIEW, section = 2,
        icon = { rememberVectorPainter(PageboardIcons.Pan) },
        isChecked = { controller.isQuickPanEnabled }
    ) { controller.toggleQuickPan() }

    /** Whether the text box suggests what may follow the caret: only with an extension that suggests. */
    val textSuggestions: Command? = if (!hasTextSuggestions) null else Command(
        "text-suggestions", label(Res.string.command_text_suggestions), CommandGroup.VIEW, section = 2,
        icon = { rememberVectorPainter(PageboardIcons.Sparkles) },
        isChecked = { controller.areTextSuggestionsEnabled }
    ) { controller.updateTextSuggestionsEnabled(!controller.areTextSuggestionsEnabled) }

    // ----- Insert -----

    val addPage = Command(
        "add-page", label(Res.string.context_menu_action_add_page), CommandGroup.INSERT,
        icon = { rememberVectorPainter(PageboardIcons.FilePlus2) },
        isEnabled = writable
    ) {
        val pages = controller.pageManager
        pages.addNewPageAfter(controller.viewport.mostVisiblePageIndex() ?: (controller.document.pages.size - 1))
    }

    /** A shape of the kind chosen in the shapes tool, in the middle of the page shown, selected. */
    val insertShape = Command(
        "insert-shape", label(Res.string.command_insert_shape), CommandGroup.INSERT, section = 2,
        icon = { rememberVectorPainter(PageboardIcons.Shapes) },
        isEnabled = writable
    ) {
        if (!controller.isDrawingMode) controller.toggleDrawingMode()
        controller.shapeInserter.insertInView()
    }

    val importPdf = Command(
        "import-pdf", label(Res.string.draw_toolbar_action_import_pdf), CommandGroup.INSERT, section = 1,
        icon = { rememberVectorPainter(PageboardIcons.FileText) },
        shortcuts = listOf(Shortcut(Key.P, primary = true, shift = true)),
        isEnabled = { writable() && controller.tileManager.tileRenderer.pdfAdapter != null }
    ) { actions.importPdf() }

    val importImage = Command(
        "import-image", label(Res.string.draw_toolbar_action_import_image), CommandGroup.INSERT, section = 1,
        icon = { rememberVectorPainter(PageboardIcons.Image) },
        shortcuts = listOf(Shortcut(Key.I, primary = true, shift = true)),
        isEnabled = writable
    ) { actions.importImage() }

    val importText = Command(
        "import-text", label(Res.string.draw_toolbar_action_import_text), CommandGroup.INSERT, section = 1,
        icon = { rememberVectorPainter(PageboardIcons.FileUp) },
        shortcuts = listOf(Shortcut(Key.T, primary = true, shift = true)),
        isEnabled = writable
    ) { actions.importText() }

    // ----- Format -----

    val documentBackground = Command(
        "document-background", label(Res.string.draw_menu_document_background), CommandGroup.FORMAT,
        icon = { rememberVectorPainter(PageboardIcons.Grid) },
        isEnabled = writable
    ) { actions.editDocumentBackground() }

    // ----- Arrange -----

    /**
     * The commands moving the selection in the stack of its page, with the shortcuts of Keynote and
     * Pages (letters: the same keys on every layout); the brackets of Figma run them too.
     */
    val bringToFront = arrangement(
        StackMove.FRONT, Res.string.command_bring_to_front, PageboardIcons.ArrowUpToLine,
        Shortcut(Key.F, primary = true, shift = true), Shortcut(Key.RightBracket, primary = true, alt = true)
    )
    val bringForward = arrangement(
        StackMove.FORWARD, Res.string.command_bring_forward, PageboardIcons.ChevronUp,
        Shortcut(Key.F, primary = true, shift = true, alt = true), primary(Key.RightBracket)
    )
    val sendBackward = arrangement(
        StackMove.BACKWARD, Res.string.command_send_backward, PageboardIcons.ChevronDown,
        Shortcut(Key.B, primary = true, shift = true, alt = true), primary(Key.LeftBracket)
    )
    val sendToBack = arrangement(
        StackMove.BACK, Res.string.command_send_to_back, PageboardIcons.ArrowDownToLine,
        Shortcut(Key.B, primary = true, shift = true), Shortcut(Key.LeftBracket, primary = true, alt = true)
    )

    /** Locks the currently selected elements so they cannot be accidentally moved, lassoed, or erased. */
    val lockSelection = Command(
        "lock-selection", label(Res.string.command_lock_selection), CommandGroup.ARRANGE, section = 1,
        icon = { rememberVectorPainter(PageboardIcons.Lock) },
        isEnabled = { hasSelection && writable() && controller.selectionManager.canLockSelection() }
    ) { controller.selectionManager.lockSelection(true) }

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
        icon = { rememberVectorPainter(PageboardIcons.Pen) },
        isEnabled = writable,
        isChecked = { controller.isDrawingMode }
    ) { controller.toggleDrawingMode() }

    val pen = tool(Tool.INK_PEN, Res.string.draw_toolbar_action_ink_pen, PageboardIcons.Pen, Key.P)
    val highlighter = tool(Tool.INK_HIGHLIGHTER, Res.string.draw_toolbar_action_highlighter, PageboardIcons.Highlighter, Key.H)
    val shapeTool = tool(Tool.SHAPES, Res.string.draw_toolbar_action_shapes, PageboardIcons.Shapes, Key.S)
    val textMarkup = tool(
        Tool.TEXT_MARKUP, Res.string.draw_toolbar_action_text_markup, PageboardIcons.TextMarkup, Key.U,
        // The text of the PDF pages, where the PDFs are drawn
        available = { controller.tileManager.tileRenderer.pdfAdapter != null && controller.document.hasPdfPages() }
    )
    val eraser = tool(Tool.ERASER, Res.string.draw_toolbar_action_eraser, PageboardIcons.Eraser, Key.E)
    val text = tool(Tool.TEXT, Res.string.draw_toolbar_action_text, PageboardIcons.Text, Key.T)
    val lasso = tool(Tool.LAZO, Res.string.draw_toolbar_action_lasso, PageboardIcons.Lasso, Key.L)
    val pan = tool(Tool.PAN, Res.string.draw_toolbar_action_pan, PageboardIcons.Pan, key = null)

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

    val bold = characterFormat(Format.BOLD, Res.string.text_editor_action_bold, PageboardIcons.Bold, primary(Key.B))
    val italic = characterFormat(Format.ITALIC, Res.string.text_editor_action_italic, PageboardIcons.Italic, primary(Key.I))
    val strikethrough = characterFormat(
        Format.STRIKETHROUGH, Res.string.text_editor_action_strikethrough, PageboardIcons.Strikethrough, Shortcut(Key.X, primary = true, shift = true)
    )
    val code = characterFormat(Format.CODE, Res.string.text_editor_action_code, PageboardIcons.Code, primary(Key.E))

    val codeBlock = Command(
        "format-code-block", label(Res.string.text_editor_action_code_block), CommandGroup.FORMAT,
        icon = { rememberVectorPainter(PageboardIcons.SquareCode) },
        shortcuts = listOf(Shortcut(Key.C, primary = true, alt = true)),
        isEnabled = { textTarget?.formatsCharacters == true },
        isChecked = { textTarget?.inCodeBlock == true }
    ) { textTarget?.toggleCodeBlock() }

    val lineNumbers = Command(
        "format-line-numbers", label(Res.string.text_editor_action_line_numbers), CommandGroup.FORMAT,
        icon = { rememberVectorPainter(PageboardIcons.Hash) },
        shortcuts = listOf(Shortcut(Key.L, primary = true, alt = true)),
        isEnabled = { textTarget?.inCodeBlock == true },
        isChecked = { textTarget?.codeLineNumbers == true }
    ) { textTarget?.toggleLineNumbers() }
    val formula = characterFormat(Format.FORMULA, Res.string.text_editor_action_math, PageboardIcons.Sigma, Shortcut(Key.M, primary = true, shift = true))

    val heading = Command(
        "format-heading", label(Res.string.text_editor_action_heading), CommandGroup.FORMAT,
        icon = { rememberVectorPainter(PageboardIcons.Heading) },
        shortcuts = listOf(Shortcut(Key.One, primary = true, alt = true)),
        isEnabled = { formatsText },
        isChecked = { textTarget?.inHeading == true }
    ) { textTarget?.toggleHeading() }

    /** Makes the line of the caret a heading of [level], or plain text for 0 (⌥⌘0 to ⌥⌘3). */
    private fun headingLevel(level: Int) = Command(
        "format-heading-$level",
        if (level == 0) label(Res.string.text_editor_action_normal_text) else ({ stringResource(Res.string.text_editor_action_heading_n, level) }),
        CommandGroup.FORMAT,
        shortcuts = listOf(Shortcut(LEVEL_KEYS[level], primary = true, alt = true)),
        isEnabled = { formatsText },
        isChecked = { textTarget?.headingLevel == level }
    ) { textTarget?.setHeading(level) }

    /** The heading levels offered (by the picker of the bar and the menu Format): normal text, then 2 and 3 (⌥⌘1 is [heading]). */
    val normalText = headingLevel(0)
    val heading2 = headingLevel(2)
    val heading3 = headingLevel(3)

    /** Whether the picker of the heading levels is open, in the formatting bar. */
    var headingLevelsOpen: Boolean by mutableStateOf(false)

    /** Opens the picker of the heading levels. */
    val headingLevels = Command(
        "format-heading-levels", label(Res.string.text_editor_action_heading_level), CommandGroup.FORMAT,
        isEnabled = { formatsText && textTarget?.inTable == false }
    ) { headingLevelsOpen = true }

    /** Makes the lines of the selection items of a checklist, or plain text again (⇧⌘9). */
    val checklist = Command(
        "format-checklist", label(Res.string.text_editor_action_checklist), CommandGroup.FORMAT,
        icon = { rememberVectorPainter(PageboardIcons.ListChecks) },
        shortcuts = listOf(Shortcut(Key.Nine, primary = true, shift = true)),
        isEnabled = { formatsText },
        isChecked = { textTarget?.inChecklist == true }
    ) { textTarget?.toggleChecklist() }

    /** Makes the lines of the selection items of a numbered checklist (`1. [ ] `), or plain text again. */
    val numberedChecklist = Command(
        "format-numbered-checklist", label(Res.string.text_editor_action_numbered_checklist), CommandGroup.FORMAT,
        icon = { rememberVectorPainter(PageboardIcons.ListOrdered) },
        isEnabled = { formatsText && textTarget?.inTable == false && textTarget?.inCodeBlock == false },
        isChecked = { textTarget.let { it != null && it.inChecklist && it.inNumberedList } }
    ) { textTarget?.toggleNumberedChecklist() }

    /** Moves the items done of the checklist of the caret below the others. */
    val moveCompletedChecklists = Command(
        "format-move-completed", label(Res.string.text_editor_action_move_completed), CommandGroup.FORMAT,
        icon = { rememberVectorPainter(PageboardIcons.ArrowDownToLine) },
        isEnabled = { formatsText && textTarget.let { it != null && it.inChecklist && it.checklistProgress.first > 0 } }
    ) { textTarget?.moveCompletedChecklistsToBottom() }

    /** Whether the options of the checklists are open, in the formatting bar. */
    var checklistOptionsOpen: Boolean by mutableStateOf(false)

    /** Opens the options of the checklists (numbered, the items done below the others). */
    val checklistOptions = Command(
        "format-checklist-options", label(Res.string.text_editor_action_checklist_options), CommandGroup.FORMAT,
        isEnabled = { formatsText && textTarget?.inTable == false }
    ) { checklistOptionsOpen = true }

    /** Checks the items of a checklist of the selection, or unchecks them (⌘↩). */
    val checkItem = Command(
        "format-check-item", label(Res.string.text_editor_action_check_item), CommandGroup.FORMAT,
        shortcuts = listOf(Shortcut(Key.Enter, primary = true)),
        isEnabled = { formatsText && textTarget?.inChecklist == true }
    ) { textTarget?.toggleChecked() }

    val bulletList = Command(
        "format-bullet-list", label(Res.string.text_editor_action_bullet_list), CommandGroup.FORMAT,
        icon = { rememberVectorPainter(PageboardIcons.List) },
        shortcuts = listOf(Shortcut(Key.Eight, primary = true, shift = true)),
        isEnabled = { formatsText },
        isChecked = { textTarget?.inBulletList == true }
    ) { textTarget?.toggleBulletList() }

    val numberedList = Command(
        "format-numbered-list", label(Res.string.text_editor_action_numbered_list), CommandGroup.FORMAT,
        icon = { rememberVectorPainter(PageboardIcons.ListOrdered) },
        shortcuts = listOf(Shortcut(Key.Seven, primary = true, shift = true)),
        isEnabled = { formatsText },
        isChecked = { textTarget?.inNumberedList == true }
    ) { textTarget?.toggleNumberedList() }

    /** Makes the lines of the selection lines of a block quote, or takes them out of it. */
    val quote = Command(
        "format-quote", label(Res.string.text_editor_action_quote), CommandGroup.FORMAT,
        icon = { rememberVectorPainter(PageboardIcons.Quote) },
        shortcuts = listOf(Shortcut(Key.Q, primary = true, alt = true)),
        isEnabled = { formatsText && textTarget?.inTable == false && textTarget?.inCodeBlock == false },
        isChecked = { textTarget?.inQuote == true }
    ) { textTarget?.toggleQuote() }

    /** Whether the picker of the styles of the numbered lists is open, in the formatting bar. */
    var numberingOpen: Boolean by mutableStateOf(false)

    /** Opens the picker of the styles of the numbered lists. */
    val numbering = Command(
        "format-numbering", label(Res.string.text_editor_action_numbering), CommandGroup.FORMAT,
        isEnabled = { formatsText && textTarget?.inTable == false }
    ) { numberingOpen = true }

    /** Whether the picker of the bullets is open, in the formatting bar. */
    var bulletsOpen: Boolean by mutableStateOf(false)

    /** Opens the picker of the bullets of the bulleted lists. */
    val bullets = Command(
        "format-bullets", label(Res.string.text_editor_action_bullets), CommandGroup.FORMAT,
        isEnabled = { formatsText && textTarget?.inTable == false }
    ) { bulletsOpen = true }

    /** Inserts a table of three rows and three columns (the bar offers its size, see `TableButton`). */
    val table = Command(
        "format-table", label(Res.string.text_editor_action_table), CommandGroup.FORMAT,
        icon = { rememberVectorPainter(PageboardIcons.Grid) },
        shortcuts = listOf(Shortcut(Key.T, primary = true, alt = true)),
        isEnabled = { formatsText && textTarget?.inTable == false },
        isChecked = { textTarget?.inTable == true }
    ) { textTarget?.insertTable(3, 3) }

    val tableRowAbove = tableAction(TableEditing.Action.ADD_ROW_ABOVE, Res.string.table_row_above)
    val tableRowBelow = tableAction(TableEditing.Action.ADD_ROW_BELOW, Res.string.table_row_below)
    val tableColumnLeft = tableAction(TableEditing.Action.ADD_COLUMN_LEFT, Res.string.table_column_left)
    val tableColumnRight = tableAction(TableEditing.Action.ADD_COLUMN_RIGHT, Res.string.table_column_right)
    val tableAlignStart = tableAction(TableEditing.Action.ALIGN_START, Res.string.table_align_start)
    val tableAlignCenter = tableAction(TableEditing.Action.ALIGN_CENTER, Res.string.table_align_center)
    val tableAlignEnd = tableAction(TableEditing.Action.ALIGN_END, Res.string.table_align_end)
    val tableDeleteRow = tableAction(TableEditing.Action.DELETE_ROW, Res.string.table_delete_row)
    val tableDeleteColumn = tableAction(TableEditing.Action.DELETE_COLUMN, Res.string.table_delete_column)
    val tableDelete = tableAction(TableEditing.Action.DELETE_TABLE, Res.string.table_delete)

    /** The commands on the table of the caret, in their sections (the menu of the table, the menu Format). */
    val tableActions: List<List<Command>> = listOf(
        listOf(tableRowAbove, tableRowBelow, tableColumnLeft, tableColumnRight),
        listOf(tableAlignStart, tableAlignCenter, tableAlignEnd),
        listOf(tableDeleteRow, tableDeleteColumn, tableDelete)
    )

    val alignStart = alignment(TextAlignment.START, Res.string.text_align_start, PageboardIcons.AlignLeft, Key.L)
    val alignCenter = alignment(TextAlignment.CENTER, Res.string.text_align_center, PageboardIcons.AlignCenter, Key.C)
    val alignEnd = alignment(TextAlignment.END, Res.string.text_align_end, PageboardIcons.AlignRight, Key.R)

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
        bold, italic, strikethrough, code, codeBlock, lineNumbers, heading, heading2, heading3, normalText, headingLevels,
        bulletList, bullets, numberedList, numbering, checklist, checkItem, numberedChecklist, moveCompletedChecklists, checklistOptions, quote, formula, table,
        alignStart, alignCenter, alignEnd, largerText, smallerText
    ) + tableActions.flatten()

    /** The command doing [action] on the table of the caret, in the third section of the menu Format. */
    private fun tableAction(action: TableEditing.Action, title: StringResource) = Command(
        id = "table-${action.name.lowercase().replace('_', '-')}",
        label = label(title),
        group = CommandGroup.FORMAT,
        section = 3,
        isEnabled = { textTarget?.inTable == true }
    ) { textTarget?.applyTableAction(action) }

    /** The command adding or removing [format] around the selection of the text being written. */
    private fun characterFormat(format: Format, title: StringResource, icon: ImageVector, shortcut: Shortcut) = Command(
        id = "format-${format.name.lowercase()}",
        label = label(title),
        group = CommandGroup.FORMAT,
        icon = { rememberVectorPainter(icon) },
        shortcuts = listOf(shortcut),
        isEnabled = { formatsText },
        isChecked = { textTarget?.isActive(format) == true }
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

    val about = Command("about", label(Res.string.about_dialog_title), CommandGroup.HELP, icon = { rememberVectorPainter(PageboardIcons.Info) }) { actions.showAbout() }

    /** Every command, in the order of the menus. */
    val all: List<Command> = listOfNotNull(
        close, rename,
        undo, redo, cut, copy, paste, pastePlain, delete, find,
        zoomIn, zoomOut, fitWidth, previousPage, nextPage, pageGrid, stylusOnly, quickPan, scrollAxisLock, snapping, shapes, ruler, protractor, layers, textSuggestions,
        addPage, importPdf, importImage, importText, insertShape,
        documentBackground,
        bringToFront, bringForward, sendBackward, sendToBack, lockSelection,
        drawingMode, pen, highlighter, shapeTool, textMarkup, eraser, text, lasso, pan, previousTool,
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

/** The keys of the heading levels 0 (normal text) to 3. */
private val LEVEL_KEYS = listOf(Key.Zero, Key.One, Key.Two, Key.Three)

private fun label(resource: StringResource): @Composable () -> String = { stringResource(resource) }

/**
 * The commands of the editor of [controller], running the actions of the screen given here (the
 * last ones given, the screen recomposing).
 */
@Composable
internal fun rememberEditorCommands(
    controller: PageboardController,
    onClose: () -> Unit,
    onImportPdf: () -> Unit,
    onImportImage: () -> Unit,
    onImportText: () -> Unit,
    onEditDocumentBackground: () -> Unit,
    onShowAbout: () -> Unit,
    canRename: Boolean = false,
    isLayersOpen: () -> Boolean = { false },
    onToggleLayers: () -> Unit = {}
): EditorCommands {
    val clipboard = LocalClipboardManager.current
    val clipboardText = rememberClipboardText()
    val close by rememberUpdatedState(onClose)
    val importPdf by rememberUpdatedState(onImportPdf)
    val importImage by rememberUpdatedState(onImportImage)
    val importText by rememberUpdatedState(onImportText)
    val editDocumentBackground by rememberUpdatedState(onEditDocumentBackground)
    val showAbout by rememberUpdatedState(onShowAbout)
    val renamable by rememberUpdatedState(canRename)
    val layersOpen by rememberUpdatedState(isLayersOpen)
    val toggleLayers by rememberUpdatedState(onToggleLayers)
    val hasTextSuggestions = LocalTextSuggestions.current != null
    return remember(controller, clipboard, clipboardText, hasTextSuggestions) {
        EditorCommands(
            controller = controller,
            clipboard = clipboard,
            actions = EditorScreenActions(
                close = { close() },
                importPdf = { importPdf() },
                importImage = { importImage() },
                importText = { importText() },
                editDocumentBackground = { editDocumentBackground() },
                showAbout = { showAbout() },
                canRename = { renamable },
                isLayersOpen = { layersOpen() },
                toggleLayers = { toggleLayers() }
            ),
            formattedText = clipboardText::markdown,
            hasTextSuggestions = hasTextSuggestions
        )
    }
}

/** Whether a page of the document holds a page of a PDF, whose text can be marked. */
internal fun Document.hasPdfPages(): Boolean = pages.any { it.pdfs.isNotEmpty() }
