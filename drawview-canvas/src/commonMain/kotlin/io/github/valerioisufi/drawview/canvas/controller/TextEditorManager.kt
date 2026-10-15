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
import io.github.valerioisufi.drawview.canvas.text.RichTextEditing
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.TextAlignment
import io.github.valerioisufi.drawview.model.Versions
import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.model.change.ChangeEffects
import io.github.valerioisufi.drawview.model.change.RemoveElements
import io.github.valerioisufi.drawview.model.change.TextData
import io.github.valerioisufi.drawview.model.change.UpdateTexts
import io.github.valerioisufi.drawview.model.change.toData
import io.github.valerioisufi.drawview.model.geometry.Point
import io.github.valerioisufi.drawview.model.newElementId
import io.github.valerioisufi.drawview.model.text.TextDefaults
import io.github.valerioisufi.drawview.model.text.TextSizes
import kotlin.math.abs
import kotlin.math.min

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

    public var activeTextPageIndex: Int by mutableIntStateOf(-1)
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

    override val formatsCharacters: Boolean get() = true

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
        open(item, pageIndex, item.x, item.y, item.width, caretAt, item.autoWidth)
    }

    private fun open(item: DrawText?, pageIndex: Int, xMm: Float, yMm: Float, widthMm: Float, caretAt: Offset?, autoWidth: Boolean) {
        if (isEditing) finishEditing()
        controller.selectionManager.clearSelection()
        controller.textMarkupManager.clearSelection()
        val page = controller.document.pages.getOrNull(pageIndex) ?: return
        activeTextItem = item
        activeTextPageIndex = pageIndex
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
        val middle = controller.viewport.screenToPageMm(pageIndex, point) ?: Offset(page.widthMm / 2f, page.heightMm / 2f)
        val defaults = controller.textDefaults
        val most = (page.widthMm - 2f * PAGE_MARGIN_MM).coerceAtLeast(MIN_WIDTH_MM)
        val probe = TextData(
            id = newElementId(), text = content, alignment = defaults.alignment, autoWidth = true,
            x = 0f, y = 0f, width = most, height = 0f, color = defaults.color, fontSize = defaults.fontSize
        )
        val size = controller.measureText(probe.toElement())
        val width = ((size?.width ?: most) + AUTO_SLACK_MM).coerceIn(AUTO_MIN_WIDTH_MM, most)
        val height = controller.measureText(probe.copy(width = width).toElement())?.height ?: (size?.height ?: MIN_HEIGHT_MM)
        val text = probe.copy(
            x = (middle.x - width / 2f).coerceIn(PAGE_MARGIN_MM, (page.widthMm - PAGE_MARGIN_MM - width).coerceAtLeast(PAGE_MARGIN_MM)),
            y = (middle.y - height / 2f).coerceIn(0f, (page.heightMm - height).coerceAtLeast(0f)),
            width = width,
            height = height.coerceAtLeast(MIN_HEIGHT_MM)
        ).toElement()
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
     * updated, an emptied one removed. The text is measured as the canvas lays it out.
     */
    public fun finishEditing() {
        val pageIndex = activeTextPageIndex
        if (pageIndex < 0) return
        val page = controller.document.pages.getOrNull(pageIndex)
        val existing = activeTextItem
        if (page == null) {
            cancelEditing()
            return
        }

        if (value.text.isBlank()) {
            existing?.let { controller.hiddenElements.setDragged(listOf(it.id), false) }
            if (existing != null) controller.session.submit(RemoveElements.of(page, listOf(existing)))
            resetSession()
            return
        }

        val laidOut = draft(versions = editedFrom?.versions ?: Versions.NONE)
        val data = laidOut.copy(height = (controller.measureText(laidOut.toElement())?.height ?: boxHeight).coerceAtLeast(MIN_HEIGHT_MM))
        if (existing != null) {
            controller.hiddenElements.setDragged(listOf(existing.id), false)
            // Shown again even when nothing changed
            existing.markDirty()
            controller.invalidatePageContent(page, existing.getRotatedBounds())
            // Changed by the user (not only on another device meanwhile)
            if (data != editedFrom) controller.session.submit(UpdateTexts(page.id, listOf(data)))
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
        val page = controller.document.pages.getOrNull(activeTextPageIndex)
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
        caretRequest = null
        value = TextFieldValue()
        controller.presence.reportTextDraft(null, null)
        controller.requestRedraw()
    }

    // ==========================================================
    // Writing and formatting
    // ==========================================================

    /** The text and its selection as the user changed them. */
    public fun updateValue(newValue: TextFieldValue) {
        if (!isEditing) return
        val textChanged = newValue.text != value.text
        value = newValue
        if (textChanged) {
            fitWidth()
            reportDraft()
        }
    }

    /** Adds or removes [format] around the selection (or inserts its delimiters at the caret). */
    override fun toggle(format: RichTextEditing.Format): Unit = updateValue(RichTextEditing.toggle(value, format))

    /** Makes the line of the caret a heading, or plain text again. */
    override fun toggleHeading(): Unit = updateValue(RichTextEditing.toggleHeading(value))

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

    /** The height (page millimeters) the editor lays the text out. */
    public fun reportLayoutHeight(heightMm: Float) {
        if (!isEditing || abs(heightMm - boxHeight) < HEIGHT_EPSILON_MM) return
        boxHeight = heightMm
        reportDraft()
    }

    // ==========================================================
    // The box
    // ==========================================================

    /** Moves the box by ([dxMm], [dyMm]) page millimeters, a part of it always on its page. */
    public fun moveBy(dxMm: Float, dyMm: Float): Unit = moveTo(boxX + dxMm, boxY + dyMm, free = true)

    /**
     * Moves the box to ([xMm], [yMm]) of its page, a part of it always on the page; unless
     * [free], snapped to what is around it (its first baseline on the rows of the paper).
     */
    public fun moveTo(xMm: Float, yMm: Float, free: Boolean = false) {
        val page = controller.document.pages.getOrNull(activeTextPageIndex) ?: return
        val box = Rect(xMm, yMm, xMm + boxWidth, yMm + boxHeight)
        val baseline = if (rotation == 0f) controller.textLineMetrics(fontSize)?.let { yMm + it.baseline } else null
        val snap = if (free) {
            controller.snapManager.clearGuides()
            Point.Zero
        } else {
            controller.snapManager.snapBox(activeTextPageIndex, box.toModel(), listOfNotNull(activeTextItem), baseline)
        }
        boxX = (xMm + snap.x).coerceIn(MIN_VISIBLE_MM - boxWidth, page.widthMm - MIN_VISIBLE_MM)
        boxY = (yMm + snap.y).coerceIn(MIN_VISIBLE_MM - boxHeight, page.heightMm - MIN_VISIBLE_MM)
        reportDraft()
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
        val most = when (alignment) {
            TextAlignment.START -> page.widthMm - PAGE_MARGIN_MM - left
            TextAlignment.CENTER -> 2f * min((left + right) / 2f - PAGE_MARGIN_MM, page.widthMm - PAGE_MARGIN_MM - (left + right) / 2f)
            TextAlignment.END -> right - PAGE_MARGIN_MM
        }.coerceAtLeast(MIN_WIDTH_MM)
        val natural = controller.measureText(draft().copy(width = most).toElement())?.width ?: return
        val width = (natural + AUTO_SLACK_MM).coerceIn(AUTO_MIN_WIDTH_MM, most)
        boxX = when (alignment) {
            TextAlignment.START -> left
            TextAlignment.CENTER -> (left + right) / 2f - width / 2f
            TextAlignment.END -> right - width
        }
        boxWidth = width
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
        height = boxHeight.coerceAtLeast(MIN_HEIGHT_MM),
        rotation = rotation,
        color = color,
        fontSize = fontSize,
        versions = versions
    )

    /** The text being written as it is now, for the others working on the document. */
    private fun reportDraft() {
        val page = controller.document.pages.getOrNull(activeTextPageIndex) ?: return
        controller.presence.reportTextDraft(page.id, draft())
    }

    private companion object {
        /** The least width of a box set by hand, and of a box following its text. */
        const val MIN_WIDTH_MM = 20f
        const val AUTO_MIN_WIDTH_MM = 5f

        /** Room for the caret after the longest line of a box following its text. */
        const val AUTO_SLACK_MM = 1f
        const val MAX_WIDTH_MM = 350f
        const val MIN_HEIGHT_MM = 4f

        /** Room left between a text following its width and the right edge of its page. */
        const val PAGE_MARGIN_MM = 5f

        /** Millimeters of a moved box that always stay on its page. */
        const val MIN_VISIBLE_MM = 5f

        /** A change of the laid out height smaller than this is not reported. */
        const val HEIGHT_EPSILON_MM = 0.01f
    }
}
