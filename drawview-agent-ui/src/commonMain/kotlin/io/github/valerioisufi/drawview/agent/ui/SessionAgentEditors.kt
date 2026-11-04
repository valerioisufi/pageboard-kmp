package io.github.valerioisufi.drawview.agent.ui

import io.github.valerioisufi.drawview.agent.AgentEditors
import io.github.valerioisufi.drawview.model.DrawPdf
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.PdfTextLayout
import io.github.valerioisufi.drawview.session.DrawViewSessions

/**
 * The editors of the process, as the external models need them (see [AgentEditors]).
 *
 * @param openDocument opens a document in an editor, from any thread.
 */
public class SessionAgentEditors(private val openDocument: (String) -> Unit) : AgentEditors {
    private fun controllerOf(documentId: String) = DrawViewSessions.find(documentId)?.controller

    override fun openForReview(documentId: String) {
        if (DrawViewSessions.find(documentId) == null) openDocument(documentId)
    }

    override fun settle(documentId: String): Boolean {
        val controller = controllerOf(documentId) ?: return false
        if (controller.textEditorManager.isEditing) controller.textEditorManager.finishEditing()
        controller.selectionManager.clearSelection()
        return true
    }

    override fun isInEditor(documentId: String): Boolean = controllerOf(documentId) != null

    override fun textHeight(documentId: String, markdown: String, widthMm: Float, fontSize: Float): Float? {
        val controller = controllerOf(documentId) ?: return null
        val text = DrawText().apply {
            this.text = markdown
            width = widthMm
            this.fontSize = fontSize
            markDirty()
        }
        return controller.measureText(text)?.height
    }

    override fun pdfLayout(documentId: String, pdf: DrawPdf): PdfTextLayout? {
        val controller = controllerOf(documentId) ?: return null
        val index = controller.document.pages.indexOfFirst { page -> page.pdfs.any { it.id == pdf.id } }
        return if (index >= 0) controller.placementManager.pdfTextOf(index) else null
    }

    /** The page drawn as a PNG by the editor of its document, or `null` when it is in none. */
    public suspend fun renderPage(documentId: String, pageId: String, maxSidePx: Int): ByteArray? =
        controllerOf(documentId)?.renderPageImage(pageId, maxSidePx)
}
