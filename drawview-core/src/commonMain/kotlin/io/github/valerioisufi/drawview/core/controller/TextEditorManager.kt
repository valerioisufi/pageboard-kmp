package io.github.valerioisufi.drawview.core.controller

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import io.github.valerioisufi.drawview.core.change.AddElements
import io.github.valerioisufi.drawview.core.change.ChangeEffects
import io.github.valerioisufi.drawview.core.change.RemoveElements
import io.github.valerioisufi.drawview.core.change.TextData
import io.github.valerioisufi.drawview.core.change.UpdateTexts
import io.github.valerioisufi.drawview.core.change.toData
import io.github.valerioisufi.drawview.core.model.DrawText
import io.github.valerioisufi.drawview.core.model.Versions
import io.github.valerioisufi.drawview.core.model.newElementId
import io.github.valerioisufi.drawview.core.text.TextSizes
import kotlin.math.min

/**
 * State of the on-canvas text editing session.
 *
 * The edited box is anchored in page millimeters ([activeTextMmX], [activeTextMmY],
 * [activeBoxWidthMm]) so that it follows the page while the canvas is panned and zoomed.
 */
class TextEditorManager(private val controller: DrawViewController) {

    var activeTextItem: DrawText? by mutableStateOf(null)
        private set

    var activeTextPageIndex: Int by mutableIntStateOf(-1)
        private set

    var activeTextMmX: Float by mutableFloatStateOf(0f)
    var activeTextMmY: Float by mutableFloatStateOf(0f)
    var activeBoxWidthMm: Float by mutableFloatStateOf(60f)

    /** Incremented at every new session (resets the editor UI state). */
    var sessionId: Int by mutableIntStateOf(0)
        private set

    var isMarkdownLivePreviewEnabled: Boolean by mutableStateOf(true)
    var isMathMode: Boolean by mutableStateOf(false)

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

    /**
     * Starts a new text on the page [pageIndex] with the caret on the point ([xMm], [yMm]) of the
     * page: the box starts there, its first line centered on the point.
     */
    fun startNewText(pageIndex: Int, xMm: Float, yMm: Float) {
        val page = controller.document.pages.getOrNull(pageIndex) ?: return
        val fontSize = controller.defaultTextFontSize
        val lineHeight = controller.textLineMetrics(fontSize)?.height ?: TextSizes.estimatedLineHeightMm(fontSize)
        val width = min(DEFAULT_WIDTH_MM, page.widthMm - xMm - PAGE_MARGIN_MM).coerceAtLeast(MIN_WIDTH_MM)
        startEditing(null, pageIndex, xMm, (yMm - lineHeight / 2f).coerceAtLeast(0f), width)
    }

    /**
     * Starts editing [item] (or a new text when `null`) on the page [pageIndex], at the given
     * page coordinates; not a text another device is writing (see [DrawViewController.writerOf]).
     *
     * @param caretAt where the user touched the text, in millimeters from the top-left corner of
     *   its box before its rotation (see `RotatableItem.toLocal`): the caret goes on the character
     *   there; at the end of the text when `null`.
     */
    fun startEditing(item: DrawText?, pageIndex: Int, xMm: Float, yMm: Float, boxWidthMm: Float, caretAt: Offset? = null) {
        item?.let { controller.writerOf(it) }?.let { writer ->
            controller.refuseEdit(writer)
            return
        }
        if (isEditing) requestFinishEditing()
        controller.selectionManager.clearSelection()
        controller.textMarkupManager.clearSelection()
        val page = controller.document.pages.getOrNull(pageIndex) ?: return
        activeTextItem = item
        activeTextPageIndex = pageIndex
        activeTextMmX = xMm
        activeTextMmY = yMm
        activeBoxWidthMm = boxWidthMm.coerceAtLeast(MIN_WIDTH_MM)
        caretRequest = caretAt
        isMathMode = item?.isLatex ?: false
        isMarkdownLivePreviewEnabled = item?.isMarkdownEnabled ?: true
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
        startEditing(text, pageIndex, text.x, text.y, text.width)
        return true
    }

    /**
     * Saves the edited text (creating, updating or deleting the text block).
     *
     * @param measuredHeightMm height of the laid out text in page millimeters.
     */
    fun finishTextEditing(
        text: String,
        isLatex: Boolean,
        color: Int,
        fontSize: Float,
        measuredHeightMm: Float,
        alignment: Int = 0
    ) {
        val pageIndex = activeTextPageIndex
        if (pageIndex < 0) return
        val page = controller.document.pages.getOrNull(pageIndex)
        val existing = activeTextItem
        if (page == null) {
            cancelTextEditing()
            return
        }

        if (text.isBlank()) {
            existing?.isDragging = false
            if (existing != null) controller.session.submit(RemoveElements.of(page, listOf(existing)))
            resetSession()
            return
        }

        val data = TextData(
            id = existing?.id ?: newTextId,
            text = text,
            isLatex = isLatex,
            isMarkdownEnabled = isMarkdownLivePreviewEnabled,
            alignment = alignment,
            x = activeTextMmX,
            y = activeTextMmY,
            width = activeBoxWidthMm.coerceAtLeast(MIN_WIDTH_MM),
            height = measuredHeightMm.coerceAtLeast(4f),
            rotation = existing?.rotation ?: 0f,
            color = color,
            fontSize = fontSize,
            versions = editedFrom?.versions ?: Versions.NONE
        )
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
     * The text being written as it is now (reported by the editor UI, which owns it), for the
     * others working on the document.
     *
     * @param heightMm height of the laid out text in page millimeters.
     */
    fun reportDraft(text: String, isLatex: Boolean, color: Int, fontSize: Float, heightMm: Float, alignment: Int = 0) {
        val page = controller.document.pages.getOrNull(activeTextPageIndex) ?: return
        val existing = activeTextItem
        val draft = TextData(
            id = existing?.id ?: newTextId,
            text = text,
            isLatex = isLatex,
            isMarkdownEnabled = isMarkdownLivePreviewEnabled,
            alignment = alignment,
            x = activeTextMmX,
            y = activeTextMmY,
            width = activeBoxWidthMm.coerceAtLeast(MIN_WIDTH_MM),
            height = heightMm.coerceAtLeast(4f),
            rotation = existing?.rotation ?: 0f,
            color = color,
            fontSize = fontSize
        )
        controller.reportTextDraft(page.id, draft)
    }

    /**
     * Saves the text being edited: registered by the editor UI, which owns the edited text.
     */
    var finishEditingRequest: (() -> Unit)? = null

    /**
     * Saves and closes the open editor (e.g. on the system back), or closes it when no editor UI
     * is attached.
     */
    fun requestFinishEditing() {
        finishEditingRequest?.invoke() ?: cancelTextEditing()
    }

    /**
     * Closes the editor discarding the changes.
     */
    fun cancelTextEditing() {
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
        if (item in effects.deletedElements) cancelTextEditing()
    }

    private fun resetSession() {
        activeTextItem = null
        activeTextPageIndex = -1
        caretRequest = null
        controller.reportTextDraft(null, null)
        controller.requestRedraw()
    }

    private companion object {
        /** Width of a new text (millimeters), less near the right edge of its page. */
        const val DEFAULT_WIDTH_MM = 70f
        const val MIN_WIDTH_MM = 20f

        /** Room left between a new text and the right edge of its page. */
        const val PAGE_MARGIN_MM = 5f
    }
}
