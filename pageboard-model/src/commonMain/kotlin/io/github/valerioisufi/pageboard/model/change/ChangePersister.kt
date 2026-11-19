package io.github.valerioisufi.pageboard.model.change

import io.github.valerioisufi.pageboard.model.CustomElement
import io.github.valerioisufi.pageboard.model.PageElement
import io.github.valerioisufi.pageboard.model.ImageElement
import io.github.valerioisufi.pageboard.model.Page
import io.github.valerioisufi.pageboard.model.PdfElement
import io.github.valerioisufi.pageboard.model.StrokeElement
import io.github.valerioisufi.pageboard.model.TextElement
import io.github.valerioisufi.pageboard.model.TextMarkupElement

/**
 * Writes the effects of the changes of a document through [persist] (a [PersistenceQueue]):
 * the persistence knows nothing of the kinds of change. The effects of an operation are written
 * with the operation in one [DocumentPersistence.transaction].
 */
public class ChangePersister(
    private val persist: (operation: suspend DocumentPersistence.() -> Unit) -> Unit
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
            transaction {
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
    }

    private suspend fun DocumentPersistence.saveElements(page: Page, elements: List<PageElement>) {
        val strokes = elements.filterIsInstance<StrokeElement>()
        if (strokes.isNotEmpty()) saveStrokes(page, strokes)
        for (element in elements) {
            when (element) {
                is StrokeElement -> {}
                is TextElement -> saveText(page, element)
                is ImageElement -> saveImage(page, element)
                is PdfElement -> savePdf(page, element)
                is TextMarkupElement -> saveTextMarkup(page, element)
                is CustomElement -> saveCustomElement(page, element)
            }
        }
    }
}
