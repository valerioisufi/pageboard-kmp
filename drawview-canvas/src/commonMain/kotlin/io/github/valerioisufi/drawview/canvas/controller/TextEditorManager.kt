package io.github.valerioisufi.drawview.canvas.controller

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.drawview.canvas.geometry.toModel
import io.github.valerioisufi.drawview.canvas.text.InlineFormatting
import io.github.valerioisufi.drawview.canvas.text.RichTextEditing
import io.github.valerioisufi.drawview.canvas.text.TableEditing
import io.github.valerioisufi.drawview.canvas.text.SelectionInput
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.TextAlignment
import io.github.valerioisufi.drawview.model.text.TextRoom
import io.github.valerioisufi.drawview.model.change.DocumentChange
import io.github.valerioisufi.drawview.model.change.AddPage
import io.github.valerioisufi.drawview.model.text.TextChains
import io.github.valerioisufi.drawview.model.text.ChainFlow
import io.github.valerioisufi.drawview.model.Versions
import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.model.change.ChangeEffects
import io.github.valerioisufi.drawview.model.change.CompositeChange
import io.github.valerioisufi.drawview.model.change.RemoveElements
import io.github.valerioisufi.drawview.model.change.ReorderElements
import io.github.valerioisufi.drawview.model.change.TextData
import io.github.valerioisufi.drawview.model.change.UpdateTexts
import io.github.valerioisufi.drawview.model.change.toData
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import io.github.valerioisufi.drawview.model.geometry.Point
import io.github.valerioisufi.drawview.model.newElementId
import io.github.valerioisufi.drawview.model.placement.TextFlow
import io.github.valerioisufi.drawview.model.text.RichTextParser
import io.github.valerioisufi.drawview.model.text.TextCleanup
import io.github.valerioisufi.drawview.model.text.TextDefaults
import io.github.valerioisufi.drawview.model.text.TextSizes
import kotlin.math.abs

/**
 * The use case "write a text": the text being written (its content, its style and its box), its
 * formatting, its box moved and resized, and the change of the document that saves it.
 *
 * The box is anchored in page millimeters ([boxX], [boxY], [boxWidth]) so that it follows the page
 * while the canvas is panned and zoomed. The editor of the interface shows [value] and reports
 * what the user types ([updateValue]) and the height it lays the text out ([reportLayoutHeight]).
 */
public class TextEditorManager(private val controller: DrawViewController) : TextFormatTarget {

    /** The text being changed, `null` for a new one. */
    internal var activeTextItem: DrawText? by mutableStateOf(null)
        private set

    /** The page the box is on: the one under its center, once it has been moved (see [moveTo]). */
    public var activeTextPageIndex: Int by mutableIntStateOf(-1)
        private set

    /** The page the text being changed is on in the document (`-1` for a new one). */
    internal var itemPageIndex: Int = -1
        private set

    /** Left of the box in page millimeters. */
    public var boxX: Float by mutableFloatStateOf(0f)
        private set

    /** Top of the box in page millimeters. */
    public var boxY: Float by mutableFloatStateOf(0f)
        private set

    /** Width of the box in page millimeters (the lines wrap on it). */
    public var boxWidth: Float by mutableFloatStateOf(MIN_WIDTH_MM)
        private set

    /** Height of the text as the editor lays it out, in page millimeters. */
    public var boxHeight: Float by mutableFloatStateOf(0f)
        private set

    /**
     * Whether the width of the box follows the text (a text started by a tap): it grows with the
     * longest line up to the margin of the page, then the lines wrap. A width set by the handles
     * or drawn stays.
     */
    public var autoWidth: Boolean by mutableStateOf(false)
        private set

    /** The text written so far, with its selection. */
    public var value: TextFieldValue by mutableStateOf(TextFieldValue())
        private set

    /** Size of the text in typographic points. */
    override var fontSize: Float by mutableFloatStateOf(16f)
        private set

    /** ARGB color of the text. */
    override var color: Int by mutableIntStateOf(0xFF000000.toInt())
        private set

    override var alignment: TextAlignment by mutableStateOf(TextAlignment.START)
        private set

    override val takesBackground: Boolean get() = true

    /** The text the one written goes on into, and whether it goes on the line of the one before (see `DrawText.next`). */
    private var chainNext: String? = null
    private var chainContinuesLine: Boolean = false

    /** The ARGB color of the fill of the box, or `null` for none (see `DrawText.background`). */
    override var background: Int? by mutableStateOf(null)
        private set

    override val formatsCharacters: Boolean get() = true

    /** The value whose block of code of the selection was last read, and that block. */
    private var codeBlockValue: TextFieldValue? = null
    private var codeBlockSelected: RichTextParser.Span? = null

    /** The block of code of the selection, read once per value. */
    private val selectedCodeBlock: RichTextParser.Span?
        get() {
            if (!isEditing) return null
            val current = value
            if (current != codeBlockValue) {
                codeBlockSelected = RichTextEditing.codeBlockAt(current)
                codeBlockValue = current
            }
            return codeBlockSelected
        }

    override val inCodeBlock: Boolean get() = selectedCodeBlock != null

    override val codeLineNumbers: Boolean get() = selectedCodeBlock?.numbered == true

    /** The value whose inline formats were last read, and those formats. */
    private var formatsValue: TextFieldValue? = null
    private var activeFormats: Set<RichTextEditing.Format> = emptySet()

    /**
     * A change made by [SelectionInput] on top of what the field typed: [before] the selection
     * was replaced, the [intermediate] value the field produced, and the value [after] surrounding.
     */
    private class SelectionStep(val before: TextFieldValue, val intermediate: TextFieldValue, val after: TextFieldValue)

    private val selectionSteps = mutableListOf<SelectionStep>()

    override fun isActive(format: RichTextEditing.Format): Boolean {
        if (!isEditing || inCodeBlock) return false
        val current = value
        if (current != formatsValue) {
            activeFormats = InlineFormatting.formatsAt(current)
            formatsValue = current
        }
        return format in activeFormats
    }

    override val inHeading: Boolean get() = isEditing && RichTextEditing.inHeading(value)

    override val inBulletList: Boolean get() = isEditing && RichTextEditing.inBulletList(value)

    override val inNumberedList: Boolean get() = isEditing && RichTextEditing.inNumberedList(value)

    /** Rotation of the text in degrees (kept: the editor does not turn a text). */
    public val rotation: Float get() = activeTextItem?.rotation ?: 0f

    /** Incremented at every new session (resets the editor UI state). */
    public var sessionId: Int by mutableIntStateOf(0)
        private set

    public val isEditing: Boolean get() = activeTextPageIndex >= 0

    /** The identifier of the text being written when it is a new one (the same for the others, see [reportDraft]). */
    private var newTextId = newElementId()

    /**
     * The text being edited as it was when the editing started: the content the change is made
     * from (one changed meanwhile on another device is kept as a copy, see `UpdateTexts`).
     */
    private var editedFrom: TextData? = null

    /**
     * Where the caret goes once the edited text is laid out: a point of the box in millimeters
     * from its top-left corner (before its rotation), or `null` for the end of the text.
     */
    private var caretRequest: Offset? = null

    /**
     * The point of the box where the user put the caret (see [startEditing]), given once: the
     * editor places the caret on the character there.
     */
    public fun takeCaretRequest(): Offset? = caretRequest.also { caretRequest = null }

    // ==========================================================
    // Opening and closing
    // ==========================================================

    /**
     * Starts a new text on the page [pageIndex] with the caret on the point ([xMm], [yMm]) of the
     * page: its first line centered on the point, the box starting there (centered on it, ending
     * there, as the alignment of a new text puts the caret), its width following the text (see
     * [autoWidth]). Unless [free], the text snaps (see [SnapManager]): on lined paper its first
     * baseline sits on the line under the point, and its edge aligns with the texts around it.
     */
    public fun startNewText(pageIndex: Int, xMm: Float, yMm: Float, free: Boolean = false) {
        controller.document.pages.getOrNull(pageIndex) ?: return
        val defaults = controller.textDefaults
        val line = controller.textLineMetrics(defaults.fontSize)
        val lineHeight = line?.height ?: TextSizes.estimatedLineHeightMm(defaults.fontSize)
        val snaps = controller.snapManager
        val row = line?.let { snaps.rowFor(pageIndex, yMm, free) }
        val top = if (row != null && line != null) row - line.baseline else yMm - lineHeight / 2f
        val caretX = xMm + snaps.snapEdge(pageIndex, xMm, top, top + lineHeight, emptyList(), free)
        snaps.clearGuides()
        val left = when (defaults.alignment) {
            TextAlignment.START -> caretX
            TextAlignment.CENTER -> caretX - AUTO_MIN_WIDTH_MM / 2f
            TextAlignment.END -> caretX - AUTO_MIN_WIDTH_MM
        }
        open(null, pageIndex, left, top.coerceAtLeast(0f), AUTO_MIN_WIDTH_MM, caretAt = null, autoWidth = true)
    }

    /**
     * Starts a new text in the box [box] (page millimeters) drawn on the page [pageIndex]: its
     * width stays the one drawn (not narrower than its minimum).
     */
    public fun startDrawnText(pageIndex: Int, box: Rect) {
        controller.document.pages.getOrNull(pageIndex) ?: return
        open(null, pageIndex, box.left, box.top, box.width, caretAt = null, autoWidth = false)
    }

    /**
     * Starts editing [item] on the page [pageIndex]; not a text another device is writing (see
     * [CollaboratorPresence.writerOf]).
     *
     * @param caretAt where the user touched the text, in millimeters from the top-left corner of
     *   its box before its rotation (see `RotatableItem.toLocal`): the caret goes on the character
     *   there; at the end of the text when `null`.
     */
    public fun startEditing(item: DrawText, pageIndex: Int, caretAt: Offset? = null) {
        controller.presence.writerOf(item)?.let { writer ->
            controller.presence.refuseEdit(writer)
            return
        }
        // On its box (the part of its room its lines take, when it follows them)
        open(item, pageIndex, item.boxX, item.boxY, item.boxWidth, caretAt, item.autoWidth)
    }

    private fun open(item: DrawText?, pageIndex: Int, xMm: Float, yMm: Float, widthMm: Float, caretAt: Offset?, autoWidth: Boolean) {
        if (isEditing) finishEditing()
        controller.selectionManager.clearSelection()
        controller.textMarkupManager.clearSelection()
        val page = controller.document.pages.getOrNull(pageIndex) ?: return
        activeTextItem = item
        activeTextPageIndex = pageIndex
        itemPageIndex = if (item != null) pageIndex else -1
        boxX = xMm
        boxY = yMm
        this.autoWidth = autoWidth
        boxWidth = widthMm.coerceAtLeast(if (autoWidth) AUTO_MIN_WIDTH_MM else MIN_WIDTH_MM)
        val defaults = controller.textDefaults
        boxHeight = item?.height ?: (controller.textLineMetrics(defaults.fontSize)?.height ?: 0f)
        val content = item?.text.orEmpty()
        value = TextFieldValue(content, TextRange(content.length))
        fontSize = item?.fontSize ?: defaults.fontSize
        color = item?.color ?: defaults.color
        background = item?.background
        chainNext = item?.next
        chainContinuesLine = item?.continuesLine == true
        alignment = item?.alignment ?: defaults.alignment
        caretRequest = caretAt
        newTextId = newElementId()
        editedFrom = item?.toData()?.copy(order = null)
        sessionId++

        if (item != null) {
            // Hide the edited text from the static layers while the editor is shown
            controller.hiddenElements.setDragged(listOf(item.id), true)
            controller.invalidatePageContent(page, item.getRotatedBounds())
        }
        fitWidth()
        controller.viewport.camera.stopAllAnimations()
        controller.requestRedraw()
        reportDraft()
    }

    /**
     * Adds [content] as a new text centered on [atScreen] (a point of the canvas, the middle of
     * the page shown when `null`), in the style of a new text, its width following the text, and
     * selects it, so that it can be moved; one change.
     */
    public fun pasteText(content: String, atScreen: Offset? = null) {
        if (content.isBlank()) return
        if (isEditing) finishEditing()
        // The page under the point, or the page shown (the first one before the canvas is laid out)
        val point = atScreen ?: Offset(controller.viewport.width / 2f, controller.viewport.height / 2f)
        val pageIndex = atScreen?.let { controller.viewport.findPageIndexAtScreen(it) } ?: controller.viewport.firstVisiblePageIndex() ?: 0
        val page = controller.document.pages.getOrNull(pageIndex) ?: return
        // A long text is imported: the student says where it goes (its sheet), then it is flowed
        if (content.length >= LONG_TEXT_CHARS && TextFlow.blocksOf(content).size > 1) {
            controller.pendingTextImport = TextImport(content, pageIndex, isMarkdown = TextCleanup.looksLikeMarkdown(content))
            return
        }
        val middle = controller.viewport.screenToPageMm(pageIndex, point) ?: Offset(page.widthMm / 2f, page.heightMm / 2f)
        val defaults = controller.textDefaults
        val most = (page.widthMm - 2f * PAGE_MARGIN_MM).coerceAtLeast(MIN_WIDTH_MM)
        val probe = TextData(
            id = newElementId(), text = content, alignment = defaults.alignment, autoWidth = true,
            x = 0f, y = 0f, width = most, color = defaults.color, fontSize = defaults.fontSize
        )
        val size = controller.measureText(probe.toElement())
        val width = TextRoom.boxWidth(size?.width ?: most, most)
        val height = size?.height ?: MIN_HEIGHT_MM
        val box = probe.copy(
            x = (middle.x - width / 2f).coerceIn(PAGE_MARGIN_MM, (page.widthMm - PAGE_MARGIN_MM - width).coerceAtLeast(PAGE_MARGIN_MM)),
            y = (middle.y - height / 2f).coerceIn(0f, (page.heightMm - height).coerceAtLeast(0f)),
            width = width
        )
        val text = inItsRoom(box, page).toElement()
        controller.session.submit(AddElements.of(page, listOf(text)))
        controller.selectionManager.selectText(pageIndex, text)
    }

    /**
     * Opens the editor on the text selected with the lasso (when the selection is a single
     * text block).
     *
     * @return `true` when the editor has been opened.
     */
    public fun startEditingSelectedText(): Boolean {
        val selection = controller.selectionManager.currentSelection ?: return false
        if (!selection.isSingleText || selection.pageIndex < 0) return false
        val text = selection.texts[0]
        val pageIndex = selection.pageIndex
        controller.selectionManager.clearSelection()
        startEditing(text, pageIndex)
        return true
    }

    /**
     * Saves the text being written and closes the editor: a new text is added, a changed one
     * updated, an emptied one removed. The text is measured as the canvas lays it out. A text
     * moved to another page goes there, on top of its elements, in the same change.
     */
    public fun finishEditing() {
        val pageIndex = activeTextPageIndex
        if (pageIndex < 0) return
        val page = controller.document.pages.getOrNull(pageIndex)
        val existing = activeTextItem
        val itemPage = if (existing != null) controller.document.pages.getOrNull(itemPageIndex) else page
        if (page == null || itemPage == null) {
            cancelEditing()
            return
        }

        // A text below the bottom of its page, or in a chain: flowed into its boxes
        if (value.text.isNotBlank() && flowChain(caret = null)) return

        if (value.text.isBlank()) {
            existing?.let { controller.hiddenElements.setDragged(listOf(it.id), false) }
            if (existing != null) controller.session.submit(RemoveElements.of(itemPage, listOf(existing)))
            resetSession()
            return
        }

        val data = inItsRoom(draft(versions = editedFrom?.versions ?: Versions.NONE), page).let { placed ->
            // Its room as it was when only the rounding of its box differs
            val from = editedFrom
            if (from != null && placed.copy(x = from.x, y = from.y, width = from.width) == from &&
                abs(placed.x - from.x) < ROUNDING_MM && abs(placed.y - from.y) < ROUNDING_MM && abs(placed.width - from.width) < ROUNDING_MM
            ) from else placed
        }
        if (existing != null) {
            controller.hiddenElements.setDragged(listOf(existing.id), false)
            // Shown again even when nothing changed
            existing.markDirty()
            controller.invalidatePageContent(itemPage, existing.getRotatedBounds())
            if (itemPage !== page) {
                // Moved to the page (see UpdateTexts), on top of its elements
                controller.session.submit(CompositeChange(listOf(UpdateTexts(page.id, listOf(data)), ReorderElements.onTop(page.id, listOf(existing.id)))))
            } else if (data != editedFrom) {
                // Changed by the user (not only on another device meanwhile)
                controller.session.submit(UpdateTexts(page.id, listOf(data)))
            }
        } else {
            controller.session.submit(AddElements.of(page, listOf(data.toElement())))
        }
        controller.updateTextDefaults(TextDefaults(fontSize, color, alignment))
        resetSession()
    }

    /**
     * Closes the editor discarding the changes.
     */
    internal fun cancelEditing() {
        val item = activeTextItem
        val page = controller.document.pages.getOrNull(itemPageIndex)
        if (item != null && page != null) {
            controller.hiddenElements.setDragged(listOf(item.id), false)
            item.markDirty()
            controller.invalidatePageContent(page, item.getRotatedBounds())
        }
        resetSession()
    }

    /** Closes the editor of a text that a change made on another device removed. */
    internal fun onChange(effects: ChangeEffects) {
        val item = activeTextItem ?: return
        if (item in effects.deletedElements) cancelEditing()
    }

    private fun resetSession() {
        activeTextItem = null
        activeTextPageIndex = -1
        itemPageIndex = -1
        caretRequest = null
        selectionSteps.clear()
        value = TextFieldValue()
        controller.presence.reportTextDraft(null, null)
        controller.requestRedraw()
    }

    // ==========================================================
    // Writing and formatting
    // ==========================================================

    /** The text and its selection as the user changed them. */
    public fun updateValue(newValue: TextFieldValue) {
        updateValue(newValue, smartNewline = true)
    }

    /**
     * The text and its selection as the user changed them, optionally applying smart list continuation
     * if [newValue] represents a single newline insertion.
     */
    public fun updateValue(newValue: TextFieldValue, smartNewline: Boolean) {
        if (!isEditing) return
        val lastStep = selectionSteps.lastOrNull()
        if (lastStep != null && value == lastStep.after && isFieldUndoOf(lastStep, newValue)) {
            selectionSteps.removeAt(selectionSteps.lastIndex)
            value = lastStep.before
            fitWidth()
            reportDraft()
            return
        }
        val textChanged = newValue.text != value.text
        val surrounded = if (textChanged && smartNewline) {
            SelectionInput.surround(value, newValue) ?: SelectionInput.link(value, newValue)
        } else {
            null
        }
        if (surrounded != null) {
            selectionSteps += SelectionStep(before = value, intermediate = newValue, after = surrounded)
        } else if (textChanged) {
            selectionSteps.clear()
        }
        val applied = when {
            surrounded != null -> surrounded
            textChanged && smartNewline -> TableEditing.keepInCells(value, newValue)
                ?: RichTextEditing.handleSmartNewline(value, newValue)
                ?: RichTextEditing.handleConcealedDeletion(value, newValue)
                ?: newValue
            textChanged -> newValue
            else -> RichTextEditing.keepCaretOutOfMarkers(value, newValue) ?: newValue
        }
        val reallyChanged = applied.text != value.text
        value = applied
        if (reallyChanged) {
            fitWidth()
            reportDraft()
            flowIfOverflowing()
        }
    }

    /**
     * Whether [newValue] is the undo of the field after [step]: the field reverts either to the
     * [SelectionStep.intermediate] value it had produced before [SelectionInput] ran, or (when the
     * text before the step had been typed within the snapshot window of the field) to an earlier
     * prefix of [SelectionStep.before].
     */
    private fun isFieldUndoOf(step: SelectionStep, newValue: TextFieldValue): Boolean {
        val beforeMin = minOf(step.before.selection.start, step.before.selection.end)
        return newValue.text == step.intermediate.text ||
            (newValue.selection.collapsed && newValue.selection.start <= beforeMin && step.before.text.startsWith(newValue.text))
    }

    /**
     * Handles Enter on the current line if it is a list item: continues the list or exits if empty.
     * Returns `true` if handled.
     */
    public fun handleEnter(): Boolean {
        if (!isEditing) return false
        val result = TableEditing.cellBelow(value) ?: RichTextEditing.handleEnter(value) ?: return false
        updateValue(result, smartNewline = false)
        return true
    }

    /**
     * Handles Shift+Enter in a list item: a line break inside the item, the next line at its text.
     * Returns `true` if handled (out of the items, a plain line break is the field's).
     */
    public fun lineBreakInItem(): Boolean {
        if (!isEditing) return false
        val result = RichTextEditing.lineBreakInItem(value) ?: return false
        updateValue(result, smartNewline = false)
        return true
    }

    /** The table of the caret, its cell (see [TableEditing.caretIn]); read once per value. */
    public val tableCaret: TableEditing.Caret?
        get() {
            if (!isEditing) return null
            val current = value
            if (current != tableValue) {
                tableCaretRead = TableEditing.caretIn(current)
                tableValue = current
            }
            return tableCaretRead
        }
    private var tableValue: TextFieldValue? = null
    private var tableCaretRead: TableEditing.Caret? = null

    override val inTable: Boolean get() = tableCaret != null

    override fun insertTable(rows: Int, columns: Int): Unit = updateValue(TableEditing.insert(value, rows, columns), smartNewline = false)

    override fun applyTableAction(action: TableEditing.Action) {
        updateValue(TableEditing.apply(value, action) ?: return, smartNewline = false)
    }

    /**
     * Moves the caret of a table: to the next cell or the previous one (Tab, Shift+Tab, [backwards]),
     * a row added after the last one. Returns `true` if the caret is in a table.
     */
    public fun tableNextCell(backwards: Boolean): Boolean {
        val result = TableEditing.nextCell(value, backwards) ?: return false
        updateValue(result, smartNewline = false)
        return true
    }

    /** Moves the caret of a table by an arrow ([up], [down], [left] or right). Returns `true` if the caret is in a table. */
    public fun tableArrow(up: Boolean = false, down: Boolean = false, left: Boolean = false): Boolean {
        val result = when {
            up || down -> TableEditing.moveVertically(value, up)
            else -> TableEditing.moveHorizontally(value, left)
        } ?: return false
        updateValue(result, smartNewline = false)
        return true
    }

    /** Adds or removes [format] around the selection (or inserts its delimiters at the caret). */
    override fun toggle(format: RichTextEditing.Format): Unit = updateValue(RichTextEditing.toggle(value, format), smartNewline = false)

    /** Makes the line of the caret a heading, or plain text again. */
    override fun toggleHeading() {
        // The lines of a table are its rows: no heading, list or block of code in them
        if (!inTable) updateValue(RichTextEditing.toggleHeading(value), smartNewline = false)
    }

    /** Puts the lines of the selection in a block of code, or takes the block of the selection out of its fences. */
    override fun toggleCodeBlock() {
        if (!inTable) updateValue(RichTextEditing.toggleCodeBlock(value), smartNewline = false)
    }

    /** Asks for the numbers of the lines of the block of code of the selection, or not any more. */
    override fun toggleLineNumbers(): Unit = updateValue(RichTextEditing.toggleLineNumbers(value), smartNewline = false)

    /** Makes the lines of the selection bullet list items, or plain text again. */
    override fun toggleBulletList() {
        if (!inTable) updateValue(RichTextEditing.toggleBulletList(value), smartNewline = false)
    }

    /** Makes the lines of the selection numbered list items, or plain text again. */
    override fun toggleNumberedList() {
        if (!inTable) updateValue(RichTextEditing.toggleNumberedList(value), smartNewline = false)
    }

    /** Adds 2 spaces of indentation to the lines of the selection. */
    override fun indent() {
        // In a table, Tab goes to the next cell
        if (!tableNextCell(backwards = false)) updateValue(RichTextEditing.indent(value), smartNewline = false)
    }

    /** Removes up to 2 spaces of indentation from the lines of the selection. */
    override fun outdent() {
        if (!tableNextCell(backwards = true)) updateValue(RichTextEditing.outdent(value), smartNewline = false)
    }

    override fun changeFontSize(size: Float) {
        val clamped = TextSizes.clamp(size)
        if (!isEditing || clamped == fontSize) return
        fontSize = clamped
        fitWidth()
        reportDraft()
    }

    override fun changeColor(argb: Int) {
        if (!isEditing || argb == color) return
        color = argb
        reportDraft()
    }

    override fun changeAlignment(newAlignment: TextAlignment) {
        if (!isEditing || newAlignment == alignment) return
        alignment = newAlignment
        reportDraft()
    }

    override fun changeBackground(argb: Int?) {
        if (!isEditing || argb == background) return
        background = argb
        reportDraft()
    }

    /** The height (page millimeters) the editor lays the text out. */
    public fun reportLayoutHeight(heightMm: Float) {
        if (!isEditing || abs(heightMm - boxHeight) < HEIGHT_EPSILON_MM) return
        boxHeight = heightMm
        reportDraft()
    }

    // ==========================================================
    // The box
    // ==========================================================

    /** Moves the box by ([dxMm], [dyMm]) page millimeters (see [moveTo]). */
    public fun moveBy(dxMm: Float, dyMm: Float): Unit = moveTo(boxX + dxMm, boxY + dyMm, free = true)

    /**
     * Moves the box to ([xMm], [yMm]) of its page; when its center is then over another page (the
     * nearest one between the pages), the box goes to that page, its corner mapped there. A part
     * of it always stays on the page it is on; unless [free], it is snapped to what is around it
     * (its first baseline on the rows of the paper).
     */
    public fun moveTo(xMm: Float, yMm: Float, free: Boolean = false) {
        if (!isEditing) return
        val corner = toPageUnderCenter(xMm, yMm)
        val page = controller.document.pages.getOrNull(activeTextPageIndex) ?: return
        val box = Rect(corner.x, corner.y, corner.x + boxWidth, corner.y + boxHeight)
        val baseline = if (rotation == 0f) controller.textLineMetrics(fontSize)?.let { corner.y + it.baseline } else null
        val snap = if (free) {
            controller.snapManager.clearGuides()
            Point.Zero
        } else {
            controller.snapManager.snapBox(activeTextPageIndex, box.toModel(), listOfNotNull(activeTextItem), baseline)
        }
        boxX = (corner.x + snap.x).coerceIn(MIN_VISIBLE_MM - boxWidth, page.widthMm - MIN_VISIBLE_MM)
        boxY = (corner.y + snap.y).coerceIn(MIN_VISIBLE_MM - boxHeight, page.heightMm - MIN_VISIBLE_MM)
        reportDraft()
    }

    /**
     * The corner ([xMm], [yMm]) of the box in the millimeters of the page under its center, which
     * becomes the page of the box ([activeTextPageIndex]). The pages share the scale of the world,
     * so the box keeps its size.
     */
    private fun toPageUnderCenter(xMm: Float, yMm: Float): Point {
        val viewport = controller.viewport
        val mmToWorld = viewport.pageMmToWorld(activeTextPageIndex) ?: return Point(xMm, yMm)
        // Its center, turned with the text around its corner
        val half = Matrix2D().postRotate(rotation).mapPoint(boxWidth / 2f, boxHeight / 2f)
        val center = mmToWorld.mapPoint(xMm + half.x, yMm + half.y)
        val target = viewport.findPageIndexAtWorld(Offset(center.x, center.y), nearest = true)
        if (target < 0 || target == activeTextPageIndex) return Point(xMm, yMm)
        val worldToTarget = Matrix2D()
        if (viewport.pageMmToWorld(target)?.invert(worldToTarget) != true) return Point(xMm, yMm)
        val world = mmToWorld.mapPoint(xMm, yMm)
        activeTextPageIndex = target
        return worldToTarget.mapPoint(world.x, world.y)
    }

    /**
     * Puts the left or the right edge of the box at [edgeXMm] of its page (the other edge staying,
     * the width not under its minimum); unless [free], snapped to what is around it. The width is
     * then set by hand (see [autoWidth]).
     */
    internal fun resizeTo(edgeXMm: Float, leftEdge: Boolean, free: Boolean = false) {
        if (!isEditing) return
        val edge = edgeXMm + if (rotation == 0f) {
            controller.snapManager.snapEdge(activeTextPageIndex, edgeXMm, boxY, boxY + boxHeight, listOfNotNull(activeTextItem), free)
        } else 0f
        resizeBy(if (leftEdge) edge - boxX else edge - (boxX + boxWidth), leftEdge)
    }

    /**
     * Moves the left or the right edge of the box by [dxMm] page millimeters: the lines wrap on
     * the new width (never less than its minimum, the other edge staying where it is).
     */
    internal fun resizeBy(dxMm: Float, leftEdge: Boolean) {
        if (!isEditing) return
        // A width set by hand stays
        autoWidth = false
        if (leftEdge) {
            val width = (boxWidth - dxMm).coerceIn(MIN_WIDTH_MM, MAX_WIDTH_MM)
            boxX += boxWidth - width
            boxWidth = width
        } else {
            boxWidth = (boxWidth + dxMm).coerceIn(MIN_WIDTH_MM, MAX_WIDTH_MM)
        }
        reportDraft()
    }

    // ==========================================================
    // Others
    // ==========================================================

    /**
     * The width of a box following its text: its longest line laid out up to the margins of the
     * page, with a little room for the caret. The box grows from the side of its alignment: its
     * left edge stays for a text aligned at the start, its middle for a centered one, its right
     * edge for one aligned at the end.
     */
    private fun fitWidth() {
        if (!autoWidth) return
        val page = controller.document.pages.getOrNull(activeTextPageIndex) ?: return
        val left = boxX
        val right = boxX + boxWidth
        val (_, most) = TextRoom.of(left, right, alignment, page.widthMm)
        val natural = controller.measureText(draft().copy(width = most).toElement())?.width ?: return
        val width = TextRoom.boxWidth(natural, most)
        boxX = when (alignment) {
            TextAlignment.START -> left
            TextAlignment.CENTER -> (left + right) / 2f - width / 2f
            TextAlignment.END -> right - width
        }
        boxWidth = width
    }

    /**
     * [box], the box of the text written, placed by its room when its width follows it (see
     * [TextRoom]): the room of the box on [page], its corner moved along its lines.
     */
    private fun inItsRoom(box: TextData, page: DrawPage): TextData {
        if (!box.autoWidth) return box
        val (left, width) = TextRoom.of(box.x, box.x + box.width, box.alignment, page.widthMm)
        val corner = Matrix2D().postRotate(box.rotation).mapPoint(left - box.x, 0f)
        return box.copy(x = box.x + corner.x, y = box.y + corner.y, width = width)
    }

    // ==========================================================
    // Chains of boxes across the pages
    // ==========================================================

    /** Whether the text written goes on into another box or comes from one (see [TextChains]). */
    public val inChain: Boolean
        get() = activeTextItem?.let { TextChains.of(controller.document).isChained(it.id) } == true

    /** Where the room of a box of a chain ends on [page]: its bottom margin. */
    private fun roomBottom(page: DrawPage): Float = page.heightMm - CHAIN_MARGIN_MM

    /** The height of [text] in the style of the text written, laid out on [widthMm]. */
    private fun heightOf(text: String, widthMm: Float): Float {
        val probe = DrawText().also {
            it.text = text
            it.width = widthMm
            it.fontSize = fontSize
            it.alignment = alignment
        }
        return controller.measureText(probe)?.height ?: TextSizes.estimatedHeightMm(text, fontSize)
    }

    /**
     * After a change of the text: when its lines go below the bottom margin of its page, the text
     * flows into the next boxes of its chain (a chain begins), and the caret goes where it is.
     */
    private fun flowIfOverflowing() {
        if (!isEditing || rotation != 0f || value.text.isBlank()) return
        val page = controller.document.pages.getOrNull(activeTextPageIndex) ?: return
        val box = inItsRoom(draft(), page)
        if (box.y + heightOf(value.text, box.width) <= roomBottom(page) + OVERFLOW_TOLERANCE_MM) return
        flowChain(caret = value.selection.start)
    }

    /**
     * Saves the text written with the boxes after it in its chain, their whole text flowed into
     * them again (see [ChainFlow]): the boxes it takes added on new pages after the last one, the
     * boxes it leaves empty removed, all in one change. With a [caret] (an offset of the text
     * written), the editor opens again on the box where that offset went.
     *
     * @return `false` when the text needs no chain (it fits on its page and goes on into none),
     *   nothing done.
     */
    private fun flowChain(caret: Int?): Boolean {
        val pageIndex = activeTextPageIndex
        val pages = controller.document.pages
        val page = pages.getOrNull(pageIndex) ?: return false
        val existing = activeTextItem
        // Turned, or moved to another page while written: saved as a text alone
        if (rotation != 0f || existing != null && itemPageIndex != pageIndex) return false
        val chains = TextChains.of(controller.document)
        val following = existing?.let { item ->
            val chain = chains.chainOf(item.id)
            chain.drop(chain.indexOfFirst { it.id == item.id } + 1)
        }.orEmpty()
        val base = inItsRoom(draft(versions = editedFrom?.versions ?: Versions.NONE), page)
        val tail = value.text + following.joinToString("") { (if (it.continuesLine) "" else "\n") + it.text }
        val pageOf = { text: DrawText -> pages[chains.pageOf(text.id)] }
        val lastPageIndex = following.lastOrNull()?.let { chains.pageOf(it.id) } ?: pageIndex
        val rooms = listOf(ChainFlow.Room(base.width, roomBottom(page) - base.y)) +
            following.map { ChainFlow.Room(it.width, roomBottom(pageOf(it)) - it.y) }
        val extra = ChainFlow.Room(base.width, roomBottom(pages[lastPageIndex]) - CHAIN_MARGIN_MM)
        val pieces = ChainFlow { text, width -> heightOf(text, width) }.flow(tail, rooms, extra)
        if (pieces.size <= 1 && following.isEmpty()) return false

        val newPages = controller.pageManager.newPagesAfter(lastPageIndex, (pieces.size - 1 - following.size).coerceAtLeast(0))
        val ids = listOf(base.id) + following.map { it.id } + newPages.map { newElementId() }
        val changes = mutableListOf<DocumentChange>()
        for (newPage in newPages) changes += AddPage.of(newPage)
        // The style of the text written for the whole chain
        fun styled(data: TextData, index: Int) = data.copy(
            text = pieces[index].text,
            next = ids.getOrNull(index + 1)?.takeIf { index + 1 < pieces.size },
            continuesLine = if (index == 0) base.continuesLine else pieces[index].continuesLine,
            fontSize = base.fontSize, color = base.color, alignment = base.alignment, background = base.background
        )
        val first = styled(base, 0)
        if (existing == null) {
            changes += AddElements.of(page, listOf(first.toElement()))
        } else if (first != editedFrom) {
            changes += UpdateTexts(page.id, listOf(first))
        }
        for (index in 1 until pieces.size) {
            val box = following.getOrNull(index - 1)
            if (box != null) {
                val before = box.toData().copy(order = null)
                val data = styled(before, index)
                if (data != before) changes += UpdateTexts(pageOf(box).id, listOf(data))
            } else {
                val newPage = newPages[index - 1 - following.size]
                val data = styled(base.copy(id = ids[index], y = CHAIN_MARGIN_MM, versions = Versions.NONE, order = null), index)
                changes += AddElements.of(newPage, listOf(data.toElement()))
            }
        }
        // The boxes the text no longer reaches
        for (box in following.drop(pieces.size - 1)) changes += RemoveElements.of(pageOf(box), listOf(box))

        existing?.let {
            controller.hiddenElements.setDragged(listOf(it.id), false)
            it.markDirty()
            controller.invalidatePageContent(page, it.getRotatedBounds())
        }
        if (changes.isNotEmpty()) controller.session.submit(CompositeChange(changes))
        controller.updateTextDefaults(TextDefaults(fontSize, color, alignment))
        resetSession()
        if (caret != null) {
            // The box of the caret: its piece of the whole text
            var start = 0
            var target = pieces.lastIndex
            for ((index, piece) in pieces.withIndex()) {
                if (index > 0 && !piece.continuesLine) start++
                if (caret <= start + piece.text.length) {
                    target = index
                    break
                }
                start += piece.text.length
            }
            openAt(ids[target], (caret - start).coerceIn(0, pieces[target].text.length), reveal = target != 0)
        }
        return true
    }

    /** Opens the editor on the text [id] with the caret at [offset]; with [reveal], its page shown. */
    private fun openAt(id: String, offset: Int, reveal: Boolean) {
        val pages = controller.document.pages
        val pageIndex = pages.indexOfFirst { page -> page.texts.any { it.id == id } }
        val text = pages.getOrNull(pageIndex)?.texts?.firstOrNull { it.id == id } ?: return
        startEditing(text, pageIndex)
        value = value.copy(selection = TextRange(offset.coerceIn(0, value.text.length)))
        if (reveal && controller.viewport.mostVisiblePageIndex() != pageIndex) controller.viewport.showPage(pageIndex)
    }

    /**
     * Moves the caret into the box before the one written in its chain (at its end) or, [forward],
     * after it (at its start), the chain saved and flowed first. Returns `true` when it did.
     */
    public fun moveInChain(forward: Boolean): Boolean {
        val item = activeTextItem ?: return false
        val chains = TextChains.of(controller.document)
        val target = (if (forward) chains.next(item.id) else chains.previous(item.id)) ?: return false
        val targetId = target.id
        if (forward) {
            finishEditing()
            openAt(targetId, 0, reveal = true)
        } else {
            // Saved from the box before, so that the chain flows from there
            finishEditing()
            openAt(targetId, Int.MAX_VALUE, reveal = true)
        }
        return isEditing
    }

    /**
     * Backspace at the start of a box that goes on from another one: the line break between them
     * taken away (the box goes on the last line of the one before, see `DrawText.continuesLine`),
     * or the last character of the box before when there is none; the caret at the end of that box.
     * Returns `true` when it did.
     */
    public fun backspaceIntoPreviousBox(): Boolean {
        val item = activeTextItem ?: return false
        val previous = TextChains.of(controller.document).previous(item.id) ?: return false
        if (!value.selection.collapsed || value.selection.start != 0) return false
        val joined = chainContinuesLine
        chainContinuesLine = true
        val previousId = previous.id
        finishEditing()
        openAt(previousId, Int.MAX_VALUE, reveal = true)
        if (joined && isEditing && value.text.isNotEmpty()) {
            val end = value.text.length
            updateValue(TextFieldValue(value.text.substring(0, end - 1), TextRange(end - 1)), smartNewline = false)
        }
        return isEditing
    }

    /** The text being written as it is now. */
    private fun draft(versions: Versions = Versions.NONE) = TextData(
        id = activeTextItem?.id ?: newTextId,
        text = value.text,
        alignment = alignment,
        autoWidth = autoWidth,
        x = boxX,
        y = boxY,
        width = boxWidth,
        rotation = rotation,
        color = color,
        fontSize = fontSize,
        background = background,
        next = chainNext,
        continuesLine = chainContinuesLine,
        versions = versions
    )

    /** The text being written as it is now, for the others working on the document. */
    private fun reportDraft() {
        val page = controller.document.pages.getOrNull(activeTextPageIndex) ?: return
        controller.presence.reportTextDraft(page.id, draft())
    }

    private companion object {
        /** The least width of a box set by hand, and of a box following its text. */
        const val MIN_WIDTH_MM = TextRoom.MIN_WIDTH_MM
        const val AUTO_MIN_WIDTH_MM = TextRoom.MIN_BOX_WIDTH_MM
        const val MAX_WIDTH_MM = 350f
        const val MIN_HEIGHT_MM = 4f

        /** Room left between a text following its width and the edges of its page. */
        const val PAGE_MARGIN_MM = TextRoom.PAGE_MARGIN_MM

        /** A pasted text at least this long, of several paragraphs, is imported (see [TextImport]). */
        const val LONG_TEXT_CHARS = 600

        /** Millimeters of a moved box that always stay on its page. */
        const val MIN_VISIBLE_MM = 5f

        /** The margin of a page under a box of a chain and above the boxes it goes on into. */
        const val CHAIN_MARGIN_MM = TextRoom.PAGE_MARGIN_MM

        /** How far the lines of a text may go below the bottom margin of its page before they flow on. */
        const val OVERFLOW_TOLERANCE_MM = 0.5f

        /** A change of the laid out height smaller than this is not reported. */
        const val HEIGHT_EPSILON_MM = 0.01f

        /** A difference of the room of a text written again within the rounding of its box. */
        const val ROUNDING_MM = 0.001f
    }
}
