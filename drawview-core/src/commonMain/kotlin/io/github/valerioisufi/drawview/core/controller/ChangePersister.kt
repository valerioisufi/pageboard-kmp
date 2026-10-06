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
        val document = committed.document
        val operation = committed.operation
        val effects = committed.effects
        if (effects.isEmpty) {
            // An operation of another device that changed nothing here: logged all the same
            persist { appendOperation(document, operation) }
            return
        }
        val resources = effects.resources.toList()
        val insertedPages = effects.insertedPages.map { it to it.elements() }
        val savedPages = effects.savedPages.toList()
        val saved = effects.savedElements.entries.groupBy({ it.value }, { it.key })
        val removedPages = effects.removedPages.toList()
        // The versions of the content of the pages (no other change of theirs)
        val versionedPages = effects.versionedPages.filter { it !in effects.savedPages && it !in effects.insertedPages && it !in effects.removedPages }
        val defaultsChanged = effects.documentDefaultsChanged
        val renamed = effects.documentRenamed
        val modifiedAt = document.modifiedAt

        // In this order: a page is stored before its elements, the files before what shows them
        persist {
            resources.forEach { saveResource(document, it) }
            for ((page, elements) in insertedPages) {
                savePage(document, page)
                saveElements(page, elements)
            }
            savedPages.forEach { savePage(document, it) }
            for ((page, elements) in saved) saveElements(page, elements)
            removedPages.forEach { savePage(document, it) }
            versionedPages.forEach { savePage(document, it) }
            if (defaultsChanged) updateDocumentDefaults(document)
            if (renamed) updateDocumentTitle(document)
            appendOperation(document, operation)
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
}
