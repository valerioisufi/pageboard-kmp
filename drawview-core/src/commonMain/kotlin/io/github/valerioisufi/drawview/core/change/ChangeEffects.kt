package io.github.valerioisufi.drawview.core.change

import androidx.compose.ui.geometry.Rect
import io.github.valerioisufi.drawview.core.model.DrawElement
import io.github.valerioisufi.drawview.core.model.DrawPage
import io.github.valerioisufi.drawview.core.model.DrawResource
import kotlin.math.max
import kotlin.math.min

/**
 * What a change did to the document, recorded while it is applied: the vocabulary of the
 * observers of a [DocumentSession] (persistence, rendering, selection), which therefore do not
 * depend on the kinds of change.
 */
class ChangeEffects internal constructor() {
    private val _savedElements = LinkedHashMap<DrawElement, DrawPage>()
    private val _deletedElements = LinkedHashSet<DrawElement>()
    private val _insertedPages = mutableListOf<Pair<DrawPage, Int>>()
    private val _removedPages = mutableListOf<Pair<DrawPage, Int>>()
    private val _formattedPages = LinkedHashSet<DrawPage>()
    private val _resources = mutableListOf<DrawResource>()
    private val _dirtyRegions = LinkedHashMap<DrawPage, Rect?>()

    /** Elements inserted or changed, with the page holding them. */
    val savedElements: Map<DrawElement, DrawPage> get() = _savedElements

    /** Elements removed from their page. */
    val deletedElements: Set<DrawElement> get() = _deletedElements

    /** Pages inserted (or put back), with their position. */
    val insertedPages: List<Pair<DrawPage, Int>> get() = _insertedPages

    /** Pages removed, with the position they had. */
    val removedPages: List<Pair<DrawPage, Int>> get() = _removedPages

    /** Pages whose format or background changed. */
    val formattedPages: Set<DrawPage> get() = _formattedPages

    /** Files added to the document. */
    val resources: List<DrawResource> get() = _resources

    /** Areas to draw again, in the millimeters of their page (`null`: the whole page). */
    val dirtyRegions: Map<DrawPage, Rect?> get() = _dirtyRegions

    var pageOrderChanged: Boolean = false
        private set

    var documentDefaultsChanged: Boolean = false
        private set

    /** The pages must be laid out again (pages added, removed, reordered or resized). */
    var layoutChanged: Boolean = false
        private set

    val isEmpty: Boolean
        get() = _savedElements.isEmpty() && _deletedElements.isEmpty() && _insertedPages.isEmpty() &&
            _removedPages.isEmpty() && _formattedPages.isEmpty() && _resources.isEmpty() &&
            !pageOrderChanged && !documentDefaultsChanged

    internal fun saved(page: DrawPage, element: DrawElement) {
        _deletedElements.remove(element)
        _savedElements[element] = page
        dirty(page, page.boundsOf(element))
    }

    internal fun deleted(page: DrawPage, element: DrawElement, bounds: Rect?) {
        _savedElements.remove(element)
        _deletedElements.add(element)
        dirty(page, bounds)
    }

    internal fun pageInserted(page: DrawPage, index: Int) {
        _insertedPages.add(page to index)
        layoutChanged = true
    }

    internal fun pageRemoved(page: DrawPage, index: Int) {
        _removedPages.add(page to index)
        layoutChanged = true
    }

    internal fun pagesReordered() {
        pageOrderChanged = true
        layoutChanged = true
    }

    internal fun pageFormatted(page: DrawPage) {
        _formattedPages.add(page)
        layoutChanged = true
        dirty(page, null)
    }

    internal fun documentDefaultsChanged() {
        documentDefaultsChanged = true
    }

    internal fun resourceAdded(resource: DrawResource) {
        _resources.add(resource)
    }

    /** Marks [rect] of [page] (the whole page when `null`) to be drawn again. */
    internal fun dirty(page: DrawPage, rect: Rect?) {
        page.markContentChanged()
        if (_dirtyRegions.containsKey(page)) {
            val previous = _dirtyRegions[page] ?: return
            _dirtyRegions[page] = rect?.let { union(previous, it) }
        } else {
            _dirtyRegions[page] = rect
        }
    }

    private fun union(a: Rect, b: Rect) = Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))
}
