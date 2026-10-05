package io.github.valerioisufi.drawview.core.controller

import io.github.valerioisufi.drawview.core.change.ChangeObserver
import io.github.valerioisufi.drawview.core.change.CommittedChange
import io.github.valerioisufi.drawview.core.model.DrawElement
import io.github.valerioisufi.drawview.core.model.DrawImage
import io.github.valerioisufi.drawview.core.model.DrawPage
import io.github.valerioisufi.drawview.core.model.DrawPdf
import io.github.valerioisufi.drawview.core.model.DrawStroke
import io.github.valerioisufi.drawview.core.model.DrawText
import io.github.valerioisufi.drawview.core.model.DrawTextMarkup

/**
 * Writes the effects of the changes of a document through [persist] (a [PersistenceQueue]):
 * the persistence knows nothing of the kinds of change.
 */
class ChangePersister(
    private val persist: (operation: suspend DrawPersistence.() -> Unit) -> Unit
) : ChangeObserver {

    override fun onChange(committed: CommittedChange) {
        val effects = committed.effects
        if (effects.isEmpty) return
        val document = committed.document
        val resources = effects.resources.toList()
        val insertedPages = effects.insertedPages.map { (page, index) -> Triple(page, index, page.elements()) }
        val saved = effects.savedElements.entries.groupBy({ it.value }, { it.key })
        val deleted = effects.deletedElements.toList()
        val removedPages = effects.removedPages.toList()
        val order = if (effects.pageOrderChanged) document.pages.toList() else null
        val formatted = effects.formattedPages.toList()
        val defaultsChanged = effects.documentDefaultsChanged
        val modifiedAt = document.modifiedAt

        // In this order: a page is stored before its elements, the files before what shows them
        persist {
            resources.forEach { saveResource(document, it) }
            for ((page, index, elements) in insertedPages) {
                insertPage(document, page, index)
                saveElements(page, elements)
            }
            for ((page, elements) in saved) saveElements(page, elements)
            deleteElements(deleted)
            for ((page, index) in removedPages) softDeletePage(document, page, index)
            order?.let { updatePagesOrder(document, it) }
            formatted.forEach { updatePageFormat(document, it) }
            if (defaultsChanged) updateDocumentDefaults(document)
            touchDocument(document, modifiedAt)
        }
    }

    private suspend fun DrawPersistence.saveElements(page: DrawPage, elements: List<DrawElement>) {
        val strokes = elements.filterIsInstance<DrawStroke>()
        if (strokes.isNotEmpty()) saveStrokes(page, strokes)
        for (element in elements) {
            when (element) {
                is DrawStroke -> {}
                is DrawText -> saveText(page, element)
                is DrawImage -> saveImage(page, element)
                is DrawPdf -> savePdf(page, element)
                is DrawTextMarkup -> saveTextMarkup(page, element)
            }
        }
    }

    private suspend fun DrawPersistence.deleteElements(elements: List<DrawElement>) {
        val strokes = elements.filterIsInstance<DrawStroke>()
        if (strokes.isNotEmpty()) deleteStrokes(strokes)
        for (element in elements) {
            when (element) {
                is DrawStroke -> {}
                is DrawText -> deleteText(element)
                is DrawImage -> deleteImage(element)
                is DrawPdf -> deletePdf(element)
                is DrawTextMarkup -> deleteTextMarkup(element)
            }
        }
    }
}
