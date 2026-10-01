package io.github.valerioisufi.drawview.core.controller

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.valerioisufi.drawview.core.history.AddTextAction
import io.github.valerioisufi.drawview.core.history.DeleteElementsAction
import io.github.valerioisufi.drawview.core.history.EditTextAction
import io.github.valerioisufi.drawview.core.model.DrawText

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

    /**
     * Starts editing [item] (or a new text when `null`) on the page [pageIndex], at the given
     * page coordinates.
     */
    fun startEditing(item: DrawText?, pageIndex: Int, xMm: Float, yMm: Float, boxWidthMm: Float) {
        if (isEditing) cancelTextEditing()
        controller.selectionManager.clearSelection()
        val page = controller.document.pages.getOrNull(pageIndex) ?: return
        activeTextItem = item
        activeTextPageIndex = pageIndex
        activeTextMmX = xMm
        activeTextMmY = yMm
        activeBoxWidthMm = boxWidthMm.coerceAtLeast(20f)
        isMathMode = item?.isLatex ?: false
        isMarkdownLivePreviewEnabled = item?.isMarkdownEnabled ?: true
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
            if (existing != null && page.texts.contains(existing)) {
                page.texts.remove(existing)
                existing.isDragging = false
                page.markContentChanged()
                controller.historyManager.addHistoryAction(DeleteElementsAction(page, emptyList(), listOf(existing), emptyList()))
                controller.persist { deleteText(existing) }
                controller.invalidatePageContent(page, existing.getRotatedBounds())
            }
            resetSession()
            return
        }

        val target = existing ?: DrawText(zIndex = page.texts.size)
        val before = existing?.let { DrawText().apply { copyFrom(it) } }
        val oldBounds = existing?.getRotatedBounds()

        target.x = activeTextMmX
        target.y = activeTextMmY
        target.text = text
        target.isLatex = isLatex
        target.isMarkdownEnabled = isMarkdownLivePreviewEnabled
        target.color = color
        target.fontSize = fontSize
        target.alignment = alignment
        target.width = activeBoxWidthMm.coerceAtLeast(20f)
        target.height = measuredHeightMm.coerceAtLeast(4f)
        target.isDragging = false
        target.markDirty()

        if (existing == null) {
            page.texts.add(target)
            controller.historyManager.addHistoryAction(AddTextAction(page, target))
        } else {
            val after = DrawText().apply { copyFrom(target) }
            controller.historyManager.addHistoryAction(EditTextAction(page, target, before!!, after))
        }
        page.markContentChanged()
        controller.persist { saveText(page, target) }
        controller.updateTextDefaults(fontSize, color)

        val dirty = oldBounds?.let {
            val b = target.getRotatedBounds()
            androidx.compose.ui.geometry.Rect(
                minOf(it.left, b.left), minOf(it.top, b.top), maxOf(it.right, b.right), maxOf(it.bottom, b.bottom)
            )
        } ?: target.getRotatedBounds()
        controller.invalidatePageContent(page, dirty)
        resetSession()
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

    private fun resetSession() {
        activeTextItem = null
        activeTextPageIndex = -1
        controller.requestRedraw()
    }
}
