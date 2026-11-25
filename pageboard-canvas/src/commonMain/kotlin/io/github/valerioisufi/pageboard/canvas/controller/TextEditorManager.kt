package io.github.valerioisufi.pageboard.canvas.controller

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.pageboard.canvas.geometry.toModel
import io.github.valerioisufi.pageboard.canvas.text.InlineFormatting
import io.github.valerioisufi.pageboard.canvas.text.RichTextEditing
import io.github.valerioisufi.pageboard.canvas.text.TableEditing
import io.github.valerioisufi.pageboard.canvas.text.SeenDeletion
import io.github.valerioisufi.pageboard.canvas.text.SelectionInput
import io.github.valerioisufi.pageboard.model.TextElement
import io.github.valerioisufi.pageboard.model.Page
import io.github.valerioisufi.pageboard.model.TextAlignment
import io.github.valerioisufi.pageboard.model.text.TextRoom
import io.github.valerioisufi.pageboard.model.change.DocumentChange
import io.github.valerioisufi.pageboard.model.change.AddPage
import io.github.valerioisufi.pageboard.model.text.TextChains
import io.github.valerioisufi.pageboard.model.text.ChainFlow
import io.github.valerioisufi.pageboard.model.Versions
import io.github.valerioisufi.pageboard.model.change.AddElements
import io.github.valerioisufi.pageboard.model.change.ChangeEffects
import io.github.valerioisufi.pageboard.model.change.CompositeChange
import io.github.valerioisufi.pageboard.model.change.RemoveElements
import io.github.valerioisufi.pageboard.model.change.RemovePage
import io.github.valerioisufi.pageboard.model.change.ReorderElements
import io.github.valerioisufi.pageboard.model.change.TextData
import io.github.valerioisufi.pageboard.model.change.UpdateTexts
import io.github.valerioisufi.pageboard.model.change.toData
import io.github.valerioisufi.pageboard.model.geometry.Matrix2D
import io.github.valerioisufi.pageboard.model.geometry.Point
import io.github.valerioisufi.pageboard.model.newElementId
import io.github.valerioisufi.pageboard.model.placement.TextFlow
import io.github.valerioisufi.pageboard.model.text.PastedText
import io.github.valerioisufi.pageboard.model.text.RichTextParser
import io.github.valerioisufi.pageboard.model.text.TextCleanup
import io.github.valerioisufi.pageboard.model.text.TextDefaults
import io.github.valerioisufi.pageboard.model.text.TextFonts
import io.github.valerioisufi.pageboard.model.text.TextSizes
import io.github.valerioisufi.pageboard.model.text.ListNumbering
import io.github.valerioisufi.pageboard.model.text.ListContinuation
import io.github.valerioisufi.pageboard.model.text.NumberingStyle
import kotlin.math.abs

/**
 * The use case "write a text": the text being written (its content, its style and its box), its
 * formatting, its box moved and resized, and the change of the document that saves it.
 *
 * The box is anchored in page millimeters ([boxX], [boxY], [boxWidth]) so that it follows the page
 * while the canvas is panned and zoomed. The editor of the interface shows [value] and reports
 * what the user types ([updateValue]) and the height it lays the text out ([reportLayoutHeight]).
 */
public class TextEditorManager internal constructor(private val controller: PageboardController) : TextFormatTarget {

    /** The text being changed, `null` for a new one. */
    internal var activeTextItem: TextElement? by mutableStateOf(null)
        private set

    /** The id of the text being changed, `null` for a new one. */
    public val activeTextId: String? get() = activeTextItem?.id

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

    override var fontFamily: String? by mutableStateOf(null)
        private set

    override val takesBackground: Boolean get() = true

    /** The text the one written goes on into, and whether it goes on the line of the one before (see `TextElement.next`). */
    private var chainNext: String? = null
    private var chainContinuesLine: Boolean = false

    /** Whether the text written goes on into a box after it (see [TextChains]). */
    public val chainGoesOn: Boolean get() = isEditing && goesOnInChain

    /** Whether the text written goes on into a box of the document, read when the editor opens (a link to none is no link). */
    private var goesOnInChain: Boolean = false

    /** Whether the text written goes on from a box before it, read when the editor opens. */
    public var chainComesFrom: Boolean = false
        private set

    /** The ARGB color of the fill of the box, or `null` for none (see `TextElement.background`). */
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

    /**
     * The value a composition of an input method started from, when the composition replaced a
     * selection (a dead key, as `` ` `` on some layouts): committed as one delimiter, it surrounds
     * that selection as the delimiter typed directly does (see [SelectionInput.surround]).
     */
    private var composedOverSelection: TextFieldValue? = null

    /** The text as typed and the list made from it, until the next edit (see [typedMarkerTakenBack]). */
    private var typedMarker: Pair<TextFieldValue, TextFieldValue>? = null

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

    override val headingLevel: Int get() = if (isEditing) RichTextEditing.headingLevelOf(value) else 0

    override val inChecklist: Boolean get() = isEditing && RichTextEditing.inChecklist(value)

    override val checklistProgress: Pair<Int, Int>
        get() = if (isEditing) RichTextEditing.checklistProgressOf(value) else 0 to 0

    override val moveCompletedToBottom: Boolean
        get() = controller.moveCompletedChecklistsToBottom

    override val inNumberedList: Boolean get() = isEditing && RichTextEditing.inNumberedList(value)

    override val inQuote: Boolean get() = isEditing && RichTextEditing.inQuote(value)

    override val numberingStyle: NumberingStyle? get() = if (isEditing) RichTextEditing.numberingStyleOf(value) else null

    override val numberingStart: Int get() = if (isEditing) RichTextEditing.numberingStartOf(value) else 1

    override val bulletStyle: ListNumbering.Bullet? get() = if (isEditing) RichTextEditing.bulletOf(value) else null

    /** Rotation of the text in degrees (kept: the editor does not turn a text). */
    public val rotation: Float get() = activeTextItem?.rotation ?: 0f

    /** Incremented at every new session (resets the editor UI state). */
    public var sessionId: Int by mutableIntStateOf(0)
        private set

    public val isEditing: Boolean get() = activeTextPageIndex >= 0

    /** The session the text last flowed out of, into another box (see [typedBeforeFlow]). */
    private var flowedFromSession: Int = -1

    /** The identifier of the text being written when it is a new one (the same for the others, see [reportDraft]). */
    private var newTextId = newElementId()

    /**
     * The text being edited as it was when the editing started: the content the change is made
     * from (one changed meanwhile on another device is kept as a copy, see `UpdateTexts`).
     */
    private var editedFrom: TextData? = null

    /** The text whose list the list of the text being written goes on from (see [ListContinuation]), or `null`. */
    private var continueNumberingFrom: String? by mutableStateOf(null)

    /**
     * Whether the writing began on a new text: saved meanwhile (see [saveWriting]) and emptied, it
     * leaves nothing to undo.
     */
    private var writingNewText = false

    /** Saves the text written as it is written (see [saveWriting]). */
    private val saver = WritingSaver(controller.coroutineScope) { saveWriting() }

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
    public fun startEditing(item: TextElement, pageIndex: Int, caretAt: Offset? = null) {
        controller.presence.writerOf(item)?.let { writer ->
            controller.presence.refuseEdit(writer)
            return
        }
        // On its box (the part of its room its lines take, when it follows them)
        open(item, pageIndex, item.boxX, item.boxY, item.boxWidth, caretAt, item.autoWidth)
    }

    private fun open(item: TextElement?, pageIndex: Int, xMm: Float, yMm: Float, widthMm: Float, caretAt: Offset?, autoWidth: Boolean) {
        if (isEditing) finishEditing()
        controller.selectionManager.clearSelection()
        controller.textMarkupManager.clearSelection()
        val page = controller.document.pages.getOrNull(pageIndex) ?: return
        // A writing goes on across the boxes it flows into (see flowChain)
        if (!controller.historyManager.hasEntry) writingNewText = item == null
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
        chainComesFrom = item != null && TextChains.of(controller.document).previous(item.id) != null
        goesOnInChain = item != null && TextChains.of(controller.document).next(item.id) != null
        alignment = item?.alignment ?: defaults.alignment
        fontFamily = TextFonts.byId(item?.fontFamily ?: defaults.fontFamily).id
        caretRequest = caretAt
        newTextId = newElementId()
        editedFrom = item?.toData()?.copy(order = null)
        continueNumberingFrom = item?.continueNumberingFrom
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
        // Opened as it is stored: nothing to save yet
        saver.stop()
    }

    /**
     * Adds [content] as a new text centered on [atScreen] (a point of the canvas, the middle of
     * the page shown when `null`), in the style of a new text, its width following the text, and
     * selects it, so that it can be moved; one change.
     */
    public fun pasteText(content: String, atScreen: Offset? = null) {
        paste(content, atScreen, isMarkdown = TextCleanup.looksLikeMarkdown(content), cleaned = PastedText.clean(content))
    }

    /**
     * As [pasteText], for [markdown] written in the notation already (what another application
     * copied with its formatting, see `docs/plans/html-paste.md`): it is taken as it is.
     */
    public fun pasteMarkdown(markdown: String, atScreen: Offset? = null) {
        paste(markdown, atScreen, isMarkdown = true, cleaned = markdown)
    }

    private fun paste(content: String, atScreen: Offset?, isMarkdown: Boolean, cleaned: String) {
        if (content.isBlank()) return
        if (isEditing) finishEditing()
        // The page under the point, or the page shown (the first one before the canvas is laid out)
        val point = atScreen ?: Offset(controller.viewport.width / 2f, controller.viewport.height / 2f)
        val pageIndex = atScreen?.let { controller.viewport.findPageIndexAtScreen(it) } ?: controller.viewport.firstVisiblePageIndex() ?: 0
        val page = controller.document.pages.getOrNull(pageIndex) ?: return
        // A long text is imported: the student says where it goes (its sheet), then it is flowed
        if (content.length >= LONG_TEXT_CHARS && TextFlow.blocksOf(content).size > 1) {
            controller.pendingTextImport = TextImport(content, pageIndex, isMarkdown = isMarkdown)
            return
        }
        val middle = controller.viewport.screenToPageMm(pageIndex, point) ?: Offset(page.widthMm / 2f, page.heightMm / 2f)
        val defaults = controller.textDefaults
        val most = (page.widthMm - 2f * PAGE_MARGIN_MM).coerceAtLeast(MIN_WIDTH_MM)
        val probe = TextData(
            id = newElementId(), text = cleaned, alignment = defaults.alignment, autoWidth = true,
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
        val text = inItsRoom(box, page).toElement().apply {
            layerId = controller.layers.writableActiveLayerId(page)
        }
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
        var keep = true
        try {
            keep = finishWriting()
        } finally {
            controller.historyManager.closeEntry(keep)
        }
    }

    /**
     * Saves the text being written as it is now, the editor staying open on it, as [finishEditing]
     * would save it alone (its chain is not flowed): so that a process killed while it is written
     * does not lose it (see `docs/plans/text-autosave.md`). A blank text, or one saved as it is, is not
     * saved; the saves of a writing are undone with it, in one step.
     */
    public fun saveWriting() {
        saver.stop()
        val pageIndex = activeTextPageIndex
        if (pageIndex < 0 || value.text.isBlank() || controller.session.isReadOnly) return
        val page = controller.document.pages.getOrNull(pageIndex) ?: return
        val existing = activeTextItem
        val itemPage = if (existing != null) controller.document.pages.getOrNull(itemPageIndex) ?: return else page
        val data = placedDraft(page)
        if (existing == null) {
            // Kept out of the tiles while written, as a text changed is
            controller.hiddenElements.setDragged(listOf(data.id), true)
            val addedElement = data.toElement().apply { layerId = controller.layers.writableActiveLayerId(page) }
            submit(AddElements.of(page, listOf(addedElement)))
            val added = page.texts.firstOrNull { it.id == data.id }
            if (added == null) {
                controller.hiddenElements.setDragged(listOf(data.id), false)
                return
            }
            activeTextItem = added
            itemPageIndex = pageIndex
        } else if (itemPage !== page) {
            // Moved to the page (see UpdateTexts), on top of its elements
            submit(CompositeChange(listOf(UpdateTexts(page.id, listOf(data)), ReorderElements.onTop(page.id, listOf(existing.id)))))
            itemPageIndex = pageIndex
        } else if (data != editedFrom) {
            submit(UpdateTexts(page.id, listOf(data)))
        } else {
            return
        }
        // The next change is made from the text as saved
        editedFrom = activeTextItem?.toData()?.copy(order = null)
    }

    /** Submits [change], a change of the text written: undone with the others of the writing. */
    private fun submit(change: DocumentChange) {
        controller.historyManager.intoEntry { controller.session.submit(change) }
    }

    /**
     * The draft placed by its room on [page], or the text as it was when only the rounding of its
     * box differs.
     */
    private fun placedDraft(page: Page): TextData {
        val placed = inItsRoom(draft(versions = editedFrom?.versions ?: Versions.NONE), page)
        val from = editedFrom
        return if (from != null && placed.copy(x = from.x, y = from.y, width = from.width) == from &&
            abs(placed.x - from.x) < ROUNDING_MM && abs(placed.y - from.y) < ROUNDING_MM && abs(placed.width - from.width) < ROUNDING_MM
        ) from else placed
    }

    /** [finishEditing] but for the entry of the history; whether the writing leaves something to undo. */
    private fun finishWriting(): Boolean {
        val pageIndex = activeTextPageIndex
        if (pageIndex < 0) return true
        val page = controller.document.pages.getOrNull(pageIndex)
        val existing = activeTextItem
        val itemPage = if (existing != null) controller.document.pages.getOrNull(itemPageIndex) else page
        if (page == null || itemPage == null) {
            cancelEditing()
            return true
        }

        // A text below the bottom of its page, or in a chain: flowed into its boxes (emptied, the
        // text of those after it comes back to it)
        val after = if (existing != null) followingInChain(existing) else emptyList()
        if ((value.text.isNotBlank() || after.any { it.text.isNotBlank() }) && flowChain(caret = null)) return true

        if (value.text.isBlank()) {
            if (existing != null) {
                controller.hiddenElements.setDragged(listOf(existing.id), false)
                submit(emptiedChange(existing, itemPage, after))
            }
            resetSession()
            // A new text saved while written, then emptied: nothing to undo
            return !writingNewText
        }

        val data = placedDraft(page)
        if (existing != null) {
            controller.hiddenElements.setDragged(listOf(existing.id), false)
            // Shown again even when nothing changed
            existing.markDirty()
            controller.invalidatePageContent(itemPage, existing.getRotatedBounds())
            if (itemPage !== page) {
                // Moved to the page (see UpdateTexts), on top of its elements
                submit(CompositeChange(listOf(UpdateTexts(page.id, listOf(data)), ReorderElements.onTop(page.id, listOf(existing.id)))))
            } else if (data != editedFrom) {
                // Changed by the user (not only on another device meanwhile)
                submit(UpdateTexts(page.id, listOf(data)))
            }
        } else {
            val addedElement = data.toElement().apply { layerId = controller.layers.writableActiveLayerId(page) }
            submit(AddElements.of(page, listOf(addedElement)))
        }
        controller.updateTextDefaults(TextDefaults(fontSize, color, alignment, fontFamily))
        resetSession()
        return true
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
        controller.historyManager.closeEntry()
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
        saver.stop()
        selectionSteps.clear()
        composedOverSelection = null
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
        // A composition starting over a selection keeps it for its commit; a commit takes it
        if (newValue.composition != null && value.composition == null && !value.selection.collapsed) composedOverSelection = value
        val composedFrom = composedOverSelection.takeIf { newValue.composition == null }
        if (newValue.composition == null) composedOverSelection = null
        val textChanged = newValue.text != value.text
        val committedOver = composedFrom?.takeIf { smartNewline }?.let { from -> SelectionInput.surround(from, newValue)?.let { from to it } }
        val surrounded = committedOver?.second ?: if (textChanged && smartNewline) {
            SelectionInput.surround(value, newValue) ?: SelectionInput.link(value, newValue) ?: cleanedPaste(newValue)
        } else {
            null
        }
        if (surrounded != null) {
            selectionSteps += SelectionStep(before = committedOver?.first ?: value, intermediate = newValue, after = surrounded)
        } else if (textChanged) {
            selectionSteps.clear()
        }
        val applied = when {
            surrounded != null -> surrounded
            textChanged && smartNewline -> typedMarkerTakenBack(newValue)
                ?: TableEditing.keepInCells(value, newValue)
                ?: RichTextEditing.handleSmartNewline(value, newValue)
                ?: RichTextEditing.listFromTypedMarker(value, newValue)?.also { typedMarker = newValue to it }
                ?: RichTextEditing.handleConcealedDeletion(value, newValue)
                ?: SeenDeletion.of(value, newValue)
                ?: newValue
            textChanged -> newValue
            else -> RichTextEditing.keepCaretOutOfMarkers(value, newValue) ?: newValue
        }.let { RichTextEditing.withListsRenumbered(value, it) }
        val reallyChanged = applied.text != value.text
        value = applied
        if (reallyChanged) {
            fitWidth()
            reportDraft()
            flowIfOverflowing()
        }
    }

    /**
     * What was typed before a list started from it, when Backspace comes right after: the text as
     * typed, staying text (its marker has one space). `null` for any other edit.
     */
    private fun typedMarkerTakenBack(newValue: TextFieldValue): TextFieldValue? {
        val (typed, list) = typedMarker ?: return null
        typedMarker = null
        val caret = list.selection.start
        return typed.takeIf { value == list && newValue.text.length == list.text.length - 1 && newValue.selection.start == caret - 1 }
    }

    /**
     * The text in the notation the field was given to paste (see `docs/plans/html-paste.md`): when it comes
     * back it is taken as it is, not cleaned as a text from elsewhere.
     */
    public var formattedPaste: String? = null

    /** [text] put at the caret in place of the selection, as a paste of the field: cleaned, in the notation of the text around it. */
    public fun pasteAtCaret(text: String) {
        if (!isEditing || text.isEmpty()) return
        formattedPaste = null
        val selection = value.selection
        val caret = selection.min + text.length
        updateValue(TextFieldValue(value.text.replaceRange(selection.min, selection.max, text), TextRange(caret)))
    }

    /**
     * [newValue] with the text pasted in it cleaned (see [PastedText]), or `null` when it needs nothing.
     * A paste is known by what it inserts: several characters with a line break, which an input
     * method does not commit; in a block of code only its line endings change, in a table it is
     * kept in its cell (see [TableEditing.keepInCells]).
     */
    private fun cleanedPaste(newValue: TextFieldValue): TextFieldValue? {
        val start = value.selection.min
        val rest = value.text.substring(value.selection.max)
        val new = newValue.text
        if (!new.startsWith(value.text.substring(0, start)) || !new.endsWith(rest) || new.length < start + rest.length + 2) return null
        val pasted = new.substring(start, new.length - rest.length)
        if (TableEditing.caretIn(value) != null) return null
        if (pasted == formattedPaste) {
            val placed = PastedText.placed(pasted, value.text.substring(0, start), rest)
            if (placed == pasted) return null
            return TextFieldValue(value.text.substring(0, start) + placed + rest, TextRange(start + placed.length))
        }
        val checkboxLine = TextCleanup.isCheckboxLine(pasted.trim())
        if ('\n' !in pasted && '\r' !in pasted && !checkboxLine) return null
        val clean = if (selectedCodeBlock != null) PastedText.normalized(pasted) else PastedText.clean(pasted)
        if (clean == pasted) return null
        return TextFieldValue(value.text.substring(0, start) + clean + rest, TextRange(start + clean.length))
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
     * A tap at ([x], [y]), in mm on the page of the box being written, past its bottom edge when its
     * text ends with a block of code, or past its top edge when it starts with one, within [reachMm]
     * of that edge and within the width of the box: a line is added after the block (before it), the
     * caret on it. The caret cannot stand outside the code on the line of a block, and a finger has
     * no arrows to add the line (see docs/plans/text-fidelity.md). Returns whether it did; the tap ends
     * the writing otherwise.
     */
    internal fun lineBesideBlockAt(x: Float, y: Float, reachMm: Float): Boolean {
        if (!isEditing) return false
        // In the box, turned around its top-left corner
        val rad = rotation * kotlin.math.PI.toFloat() / 180f
        val c = kotlin.math.cos(rad)
        val s = kotlin.math.sin(rad)
        val dx = x - boxX
        val dy = y - boxY
        val localX = dx * c + dy * s
        val localY = -dx * s + dy * c
        if (localX < 0f || localX > boxWidth) return false
        val text = value.text
        val blocks = RichTextParser.parse(text).filter { it.kind == RichTextParser.Kind.CODE_BLOCK && it.contentEnd < it.end }
        val next = when {
            localY > boxHeight && localY <= boxHeight + reachMm && blocks.any { it.end == text.length } ->
                TextFieldValue(text + "\n", TextRange(text.length + 1))
            localY < 0f && localY >= -reachMm && blocks.any { it.start == 0 } -> TextFieldValue("\n" + text, TextRange(0))
            else -> return false
        }
        updateValue(next, smartNewline = false)
        return true
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

    /** Makes the line of the caret a heading of [level] (1 to 6), or plain text for 0. */
    override fun setHeading(level: Int) {
        if (!inTable) updateValue(RichTextEditing.setHeading(value, level), smartNewline = false)
    }

    /** Makes the lines of the selection items of a checklist, or plain text again. */
    override fun toggleChecklist() {
        if (!inTable) updateValue(RichTextEditing.toggleChecklist(value), smartNewline = false)
    }

    /** Checks the items of a checklist of the selection, or unchecks them when they are all checked. */
    override fun toggleChecked() {
        if (inTable) return
        val toggled = RichTextEditing.toggleChecked(value)
        val sorted = if (controller.moveCompletedChecklistsToBottom) RichTextEditing.moveCompletedToEnd(toggled) else toggled
        updateValue(sorted, smartNewline = false)
    }

    /** Checks or unchecks the item of a checklist starting at [itemStart] of the text (a tap on its box). */
    public fun toggleCheckedAt(itemStart: Int) {
        val toggled = RichTextEditing.toggleCheckedAt(value, itemStart)
        val sorted = if (controller.moveCompletedChecklistsToBottom) RichTextEditing.moveCompletedToEnd(toggled, onlyItemStart = itemStart) else toggled
        updateValue(sorted, smartNewline = false)
    }

    /** Makes the lines of the selection numbered checklist items (`1. [ ] `), or plain text again. */
    override fun toggleNumberedChecklist() {
        if (!inTable) updateValue(RichTextEditing.toggleNumberedChecklist(value), smartNewline = false)
    }

    /** Moves completed checklist items to the bottom of their list. */
    override fun moveCompletedChecklistsToBottom() {
        if (!inTable) updateValue(RichTextEditing.moveCompletedToEnd(value), smartNewline = false)
    }

    /** Enables or disables automatically moving completed checklist items to the bottom when checked. */
    override fun setMoveCompletedToBottom(enabled: Boolean) {
        controller.updateMoveCompletedChecklistsToBottom(enabled)
        if (enabled && isEditing && checklistProgress.first > 0) moveCompletedChecklistsToBottom()
    }

    /** Makes the lines of the selection numbered list items, or plain text again. */
    override fun toggleNumberedList() {
        if (!inTable) updateValue(RichTextEditing.toggleNumberedList(value), smartNewline = false)
    }

    /** Makes the lines of the selection lines of a block quote, or takes them out of it. */
    override fun toggleQuote() {
        if (!inTable) updateValue(RichTextEditing.toggleQuote(value), smartNewline = false)
    }

    /** The list of the selection in [style] from [start], made a numbered list if it is not one. */
    override fun setNumbering(style: NumberingStyle?, start: Int) {
        if (!inTable) updateValue(RichTextEditing.setNumbering(value, style, start), smartNewline = false)
    }

    /** The bulleted list of the selection with [bullet] (`null`: the bullet of its level), made one if it is not. */
    override fun setBullet(bullet: ListNumbering.Bullet?) {
        if (!inTable) updateValue(RichTextEditing.setBullet(value, bullet), smartNewline = false)
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

    /** The number the first numbered list of the text being written starts from, going on from another text; `null` for none. */
    public val continuedListStart: Int?
        get() {
            val from = continueNumberingFrom ?: return null
            val texts = controller.document.pages.asSequence().flatMap { it.texts }
            return ListContinuation.startOf(from, activeTextItem?.id ?: newTextId) { id -> texts.firstOrNull { it.id == id } }
        }

    override val continuesNumbering: Boolean get() = isEditing && continueNumberingFrom != null

    override val canContinueNumbering: Boolean
        get() = isEditing && (continueNumberingFrom != null || previousListText() != null)

    /** The first numbered list of the text being written going on from the list of the text before it, or counting on its own. */
    override fun setContinuesNumbering(continues: Boolean) {
        if (!isEditing) return
        val from = if (continues) previousListText()?.id ?: return else null
        if (from == continueNumberingFrom) return
        continueNumberingFrom = from
        reportDraft()
    }

    /** The text before the one being written with a numbered list, which its list may go on from. */
    private fun previousListText(): TextElement? {
        val page = controller.document.pages.getOrNull(activeTextPageIndex) ?: return null
        return ListContinuation.previousListText(controller.document, page, activeTextItem?.id ?: newTextId, boxY)
    }

    override fun changeFontFamily(fontFamily: String?) {
        val normalized = TextFonts.byId(fontFamily).id
        if (!isEditing || normalized == this.fontFamily) return
        this.fontFamily = normalized
        fitWidth()
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
    private fun inItsRoom(box: TextData, page: Page): TextData {
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
    private fun roomBottom(page: Page): Float = page.heightMm - CHAIN_MARGIN_MM

    /** The height of [text] in the style of the text written, laid out on [widthMm]. */
    private fun heightOf(text: String, widthMm: Float): Float {
        val probe = TextElement().also {
            it.text = text
            it.width = widthMm
            it.fontSize = fontSize
            it.alignment = alignment
        }
        return controller.measureText(probe)?.height ?: TextSizes.estimatedHeightMm(text, fontSize)
    }

    /** The boxes after [item] in its chain. */
    private fun followingInChain(item: TextElement): List<TextElement> {
        val chain = TextChains.of(controller.document).chainOf(item.id)
        return chain.drop(chain.indexOfFirst { it.id == item.id } + 1)
    }

    /**
     * The change removing the box [existing] of [page], emptied, with the empty boxes [after] it: the
     * box before it no longer goes on into it, and the pages they leave blank go too.
     */
    private fun emptiedChange(existing: TextElement, page: Page, after: List<TextElement>): DocumentChange {
        val previous = TextChains.of(controller.document).previous(existing.id)
        val changes = mutableListOf<DocumentChange>()
        if (previous != null) {
            val pageOfPrevious = controller.document.pages.first { p -> p.texts.any { it.id == previous.id } }
            changes += UpdateTexts(pageOfPrevious.id, listOf(previous.toData().copy(order = null, next = null)))
            changes += removedBoxes(listOf(existing) + after)
        } else {
            changes += RemoveElements.of(page, listOf(existing))
            changes += removedBoxes(after)
        }
        return if (changes.size == 1) changes[0] else CompositeChange(changes)
    }

    /**
     * The change removing [boxes], boxes a chain no longer reaches, with the pages they leave blank:
     * with nothing else on them, and the paper of the document. They go to the trash, and one undo
     * brings them back.
     */
    private fun removedBoxes(boxes: List<TextElement>): List<DocumentChange> {
        val pages = controller.document.pages
        val changes = mutableListOf<DocumentChange>()
        var remaining = pages.size
        for (page in pages) {
            val removed = boxes.filter { box -> page.texts.any { it.id == box.id } }
            if (removed.isEmpty()) continue
            changes += RemoveElements.of(page, removed)
            val blank = page.strokes.isEmpty() && page.images.isEmpty() && page.pdfs.isEmpty() && page.markups.isEmpty() && page.customElements.isEmpty() &&
                page.texts.size == removed.size && !page.hasCustomBackground
            if (blank && remaining > 1) {
                changes += RemovePage(page.id)
                remaining--
            }
        }
        return changes
    }

    /**
     * After a change of the text: when its lines go below the bottom margin of its page, the text
     * flows into the next boxes of its chain (a chain begins), and the caret goes where it is.
     */
    private fun flowIfOverflowing() {
        if (!isEditing || rotation != 0f || value.text.isBlank()) return
        val page = controller.document.pages.getOrNull(activeTextPageIndex) ?: return
        val box = inItsRoom(draft(), page)
        // Blank lines at the end start no box: the next character does
        if (box.y + heightOf(value.text.trimEnd('\n'), box.width) <= roomBottom(page) + OVERFLOW_TOLERANCE_MM) return
        flowChain(caret = value.selection.start)
    }

    /**
     * What the field of the session [fieldSession] changed from the text [previous] it still had,
     * after the text flowed into another box in the same frame (see [flowChain]): the same change
     * at the caret of the box written now, so that no key is lost.
     */
    public fun typedBeforeFlow(fieldSession: Int, previous: String, newValue: TextFieldValue) {
        if (!isEditing || fieldSession != flowedFromSession) return
        val new = newValue.text
        val shortest = minOf(previous.length, new.length)
        var start = 0
        while (start < shortest && previous[start] == new[start]) start++
        var end = 0
        while (end < shortest - start && previous[previous.length - 1 - end] == new[new.length - 1 - end]) end++
        val inserted = new.substring(start, new.length - end)
        val removed = previous.length - start - end
        val caret = value.selection.min
        val from = (caret - removed).coerceAtLeast(0)
        updateValue(TextFieldValue(value.text.substring(0, from) + inserted + value.text.substring(caret), TextRange(from + inserted.length)))
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
        val following = existing?.let(::followingInChain).orEmpty()
        val base = inItsRoom(draft(versions = editedFrom?.versions ?: Versions.NONE), page)
        // A box emptied leaves no line of its own before the text coming back to it
        val tail = (value.text + following.joinToString("") { (if (it.continuesLine) "" else "\n") + it.text })
            .let { if (value.text.isBlank()) it.substring(value.text.length).removePrefix("\n") else it }
        val pageOf = { text: TextElement -> pages[chains.pageOf(text.id)] }
        val lastPageIndex = following.lastOrNull()?.let { chains.pageOf(it.id) } ?: pageIndex
        val rooms = listOf(ChainFlow.Room(base.width, roomBottom(page) - base.y)) +
            following.map { ChainFlow.Room(it.width, roomBottom(pageOf(it)) - it.y) }
        val extra = ChainFlow.Room(base.width, roomBottom(pages[lastPageIndex]) - CHAIN_MARGIN_MM)
        val flowed = ChainFlow { text, width -> heightOf(text, width) }.flow(tail, rooms, extra)
        // Saved with no caret to hold on them, blank lines at the end start no box
        val pieces = if (caret == null) flowed.dropLastWhile { it.text.isBlank() }.ifEmpty { flowed.take(1) } else flowed
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
        // The boxes the text no longer reaches, and the pages they leave blank
        changes += removedBoxes(following.drop(pieces.size - 1))

        existing?.let {
            controller.hiddenElements.setDragged(listOf(it.id), false)
            it.markDirty()
            controller.invalidatePageContent(page, it.getRotatedBounds())
        }
        if (changes.isNotEmpty()) submit(CompositeChange(changes))
        controller.updateTextDefaults(TextDefaults(fontSize, color, alignment, fontFamily))
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
            val session = sessionId
            openAt(ids[target], (caret - start).coerceIn(0, pieces[target].text.length))
            flowedFromSession = session
            // The writing goes on in that box, or ends here
            if (!isEditing) controller.historyManager.closeEntry()
        }
        return true
    }

    /** Opens the editor on the text [id] with the caret at [offset]: the interface brings the caret in sight. */
    private fun openAt(id: String, offset: Int) {
        val pages = controller.document.pages
        val pageIndex = pages.indexOfFirst { page -> page.texts.any { it.id == id } }
        val text = pages.getOrNull(pageIndex)?.texts?.firstOrNull { it.id == id } ?: return
        startEditing(text, pageIndex)
        value = value.copy(selection = TextRange(offset.coerceIn(0, value.text.length)))
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
            openAt(targetId, 0)
        } else {
            // Saved from the box before, so that the chain flows from there
            finishEditing()
            openAt(targetId, Int.MAX_VALUE)
        }
        return isEditing
    }

    /**
     * Backspace at the start of a box that goes on from another one: the line break between them
     * taken away (the box goes on the last line of the one before, see `TextElement.continuesLine`),
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
        openAt(previousId, Int.MAX_VALUE)
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
        fontFamily = fontFamily,
        // What the editor does not change is kept as it was
        continueNumberingFrom = continueNumberingFrom,
        maxHeight = editedFrom?.maxHeight,
        versions = versions
    )

    /** The text being written as it is now, for the others working on the document, and saved soon (see [WritingSaver]). */
    private fun reportDraft() {
        val page = controller.document.pages.getOrNull(activeTextPageIndex) ?: return
        controller.presence.reportTextDraft(page.id, draft())
        saver.changed()
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
