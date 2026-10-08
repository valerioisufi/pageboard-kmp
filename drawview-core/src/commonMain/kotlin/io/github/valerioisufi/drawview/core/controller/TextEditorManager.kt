package io.github.valerioisufi.drawview.core.controller

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.drawview.core.change.AddElements
import io.github.valerioisufi.drawview.core.change.ChangeEffects
import io.github.valerioisufi.drawview.core.change.RemoveElements
import io.github.valerioisufi.drawview.core.change.TextData
import io.github.valerioisufi.drawview.core.change.UpdateTexts
import io.github.valerioisufi.drawview.core.change.toData
import io.github.valerioisufi.drawview.core.model.DrawText
import io.github.valerioisufi.drawview.core.model.TextAlignment
import io.github.valerioisufi.drawview.core.model.Versions
import io.github.valerioisufi.drawview.core.model.newElementId
import io.github.valerioisufi.drawview.core.text.RichTextEditing
import io.github.valerioisufi.drawview.core.text.TextSizes
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
class TextEditorManager(private val controller: DrawViewController) {

    /** The text being changed, `null` for a new one. */
    var activeTextItem: DrawText? by mutableStateOf(null)
        private set

    var activeTextPageIndex: Int by mutableIntStateOf(-1)
        private set

    /** Left of the box in page millimeters. */
    var boxX: Float by mutableFloatStateOf(0f)
        private set

    /** Top of the box in page millimeters. */
    var boxY: Float by mutableFloatStateOf(0f)
        private set

    /** Width of the box in page millimeters (the lines wrap on it). */
    var boxWidth: Float by mutableFloatStateOf(DEFAULT_WIDTH_MM)
        private set

    /** Height of the text as the editor lays it out, in page millimeters. */
    var boxHeight: Float by mutableFloatStateOf(0f)
        private set

    /** The text written so far, with its selection. */
    var value: TextFieldValue by mutableStateOf(TextFieldValue())
        private set

    /** Size of the text in typographic points. */
    var fontSize: Float by mutableFloatStateOf(16f)
        private set

    /** ARGB color of the text. */
    var color: Int by mutableIntStateOf(0xFF000000.toInt())
        private set

    var alignment: TextAlignment by mutableStateOf(TextAlignment.START)
        private set

    /** Rotation of the text in degrees (kept: the editor does not turn a text). */
    val rotation: Float get() = activeTextItem?.rotation ?: 0f

    /** Incremented at every new session (resets the editor UI state). */
    var sessionId: Int by mutableIntStateOf(0)
        private set

    val isEditing: Boolean get() = activeTextPageIndex >= 0

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
    fun takeCaretRequest(): Offset? = caretRequest.also { caretRequest = null }

    // ==========================================================
    // Opening and closing
    // ==========================================================

    /**
     * Starts a new text on the page [pageIndex] with the caret on the point ([xMm], [yMm]) of the
     * page: the box starts there, its first line centered on the point.
     */
    fun startNewText(pageIndex: Int, xMm: Float, yMm: Float) {
        val page = controller.document.pages.getOrNull(pageIndex) ?: return
        val size = controller.defaultTextFontSize
        val lineHeight = controller.textLineMetrics(size)?.height ?: TextSizes.estimatedLineHeightMm(size)
        val width = min(DEFAULT_WIDTH_MM, page.widthMm - xMm - PAGE_MARGIN_MM).coerceAtLeast(MIN_WIDTH_MM)
        open(null, pageIndex, xMm, (yMm - lineHeight / 2f).coerceAtLeast(0f), width, caretAt = null)
    }

    /**
     * Starts editing [item] on the page [pageIndex]; not a text another device is writing (see
     * [DrawViewController.writerOf]).
     *
     * @param caretAt where the user touched the text, in millimeters from the top-left corner of
     *   its box before its rotation (see `RotatableItem.toLocal`): the caret goes on the character
     *   there; at the end of the text when `null`.
     */
    fun startEditing(item: DrawText, pageIndex: Int, caretAt: Offset? = null) {
        controller.writerOf(item)?.let { writer ->
            controller.refuseEdit(writer)
            return
        }
        open(item, pageIndex, item.x, item.y, item.width, caretAt)
    }

    private fun open(item: DrawText?, pageIndex: Int, xMm: Float, yMm: Float, widthMm: Float, caretAt: Offset?) {
        if (isEditing) finishEditing()
        controller.selectionManager.clearSelection()
        controller.textMarkupManager.clearSelection()
        val page = controller.document.pages.getOrNull(pageIndex) ?: return
        activeTextItem = item
        activeTextPageIndex = pageIndex
        boxX = xMm
        boxY = yMm
        boxWidth = widthMm.coerceAtLeast(MIN_WIDTH_MM)
        boxHeight = item?.height ?: (controller.textLineMetrics(controller.defaultTextFontSize)?.height ?: 0f)
        val content = item?.text.orEmpty()
        value = TextFieldValue(content, TextRange(content.length))
        fontSize = item?.fontSize ?: controller.defaultTextFontSize
        color = item?.color ?: controller.defaultTextColor
        alignment = item?.alignment ?: TextAlignment.START
        caretRequest = caretAt
        newTextId = newElementId()
        editedFrom = item?.toData()?.copy(order = null)
        sessionId++

        if (item != null) {
            // Hide the edited text from the static layers while the editor is shown
            item.isDragging = true
            controller.invalidatePageContent(page, item.getRotatedBounds())
        }
        controller.cameraPhysics.stopAllAnimations()
        controller.requestRedraw()
        reportDraft()
    }

    /**
     * Opens the editor on the text selected with the lasso (when the selection is a single
     * text block).
     *
     * @return `true` when the editor has been opened.
     */
    fun startEditingSelectedText(): Boolean {
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
    fun finishEditing() {
        val pageIndex = activeTextPageIndex
        if (pageIndex < 0) return
        val page = controller.document.pages.getOrNull(pageIndex)
        val existing = activeTextItem
        if (page == null) {
            cancelEditing()
            return
        }

        if (value.text.isBlank()) {
            existing?.isDragging = false
            if (existing != null) controller.session.submit(RemoveElements.of(page, listOf(existing)))
            resetSession()
            return
        }

        val laidOut = draft(versions = editedFrom?.versions ?: Versions.NONE)
        val data = laidOut.copy(height = (controller.measureText(laidOut.toElement())?.height ?: boxHeight).coerceAtLeast(MIN_HEIGHT_MM))
        if (existing != null) {
            existing.isDragging = false
            // Shown again even when nothing changed
            existing.markDirty()
            controller.invalidatePageContent(page, existing.getRotatedBounds())
            // Changed by the user (not only on another device meanwhile)
            if (data != editedFrom) controller.session.submit(UpdateTexts(page.id, listOf(data)))
        } else {
            controller.session.submit(AddElements.of(page, listOf(data.toElement())))
        }
        controller.updateTextDefaults(fontSize, color)
        resetSession()
    }

    /**
     * Closes the editor discarding the changes.
     */
    fun cancelEditing() {
        val item = activeTextItem
        val page = controller.document.pages.getOrNull(activeTextPageIndex)
        if (item != null && page != null) {
            item.isDragging = false
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
        controller.reportTextDraft(null, null)
        controller.requestRedraw()
    }

    // ==========================================================
    // Writing and formatting
    // ==========================================================

    /** The text and its selection as the user changed them. */
    fun updateValue(newValue: TextFieldValue) {
        if (!isEditing) return
        val textChanged = newValue.text != value.text
        value = newValue
        if (textChanged) reportDraft()
    }

    /** Adds or removes [format] around the selection (or inserts its delimiters at the caret). */
    fun toggle(format: RichTextEditing.Format) = updateValue(RichTextEditing.toggle(value, format))

    /** Makes the line of the caret a heading, or plain text again. */
    fun toggleHeading() = updateValue(RichTextEditing.toggleHeading(value))

    fun changeFontSize(size: Float) {
        if (!isEditing || size == fontSize) return
        fontSize = size
        reportDraft()
    }

    fun changeColor(argb: Int) {
        if (!isEditing || argb == color) return
        color = argb
        reportDraft()
    }

    fun changeAlignment(newAlignment: TextAlignment) {
        if (!isEditing || newAlignment == alignment) return
        alignment = newAlignment
        reportDraft()
    }

    /** The height (page millimeters) the editor lays the text out. */
    fun reportLayoutHeight(heightMm: Float) {
        if (!isEditing || abs(heightMm - boxHeight) < HEIGHT_EPSILON_MM) return
        boxHeight = heightMm
        reportDraft()
    }

    // ==========================================================
    // The box
    // ==========================================================

    /** Moves the box by ([dxMm], [dyMm]) page millimeters, a part of it always on its page. */
    fun moveBy(dxMm: Float, dyMm: Float) {
        val page = controller.document.pages.getOrNull(activeTextPageIndex) ?: return
        boxX = (boxX + dxMm).coerceIn(MIN_VISIBLE_MM - boxWidth, page.widthMm - MIN_VISIBLE_MM)
        boxY = (boxY + dyMm).coerceIn(MIN_VISIBLE_MM - boxHeight, page.heightMm - MIN_VISIBLE_MM)
        reportDraft()
    }

    /**
     * Moves the left or the right edge of the box by [dxMm] page millimeters: the lines wrap on
     * the new width (never less than its minimum, the other edge staying where it is).
     */
    fun resizeBy(dxMm: Float, leftEdge: Boolean) {
        if (!isEditing) return
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

    /** The text being written as it is now. */
    private fun draft(versions: Versions = Versions.NONE) = TextData(
        id = activeTextItem?.id ?: newTextId,
        text = value.text,
        alignment = alignment,
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
        controller.reportTextDraft(page.id, draft())
    }

    private companion object {
        /** Width of a new text (millimeters), less near the right edge of its page. */
        const val DEFAULT_WIDTH_MM = 70f
        const val MIN_WIDTH_MM = 20f
        const val MAX_WIDTH_MM = 350f
        const val MIN_HEIGHT_MM = 4f

        /** Room left between a new text and the right edge of its page. */
        const val PAGE_MARGIN_MM = 5f

        /** Millimeters of a moved box that always stay on its page. */
        const val MIN_VISIBLE_MM = 5f

        /** A change of the laid out height smaller than this is not reported. */
        const val HEIGHT_EPSILON_MM = 0.01f
    }
}
