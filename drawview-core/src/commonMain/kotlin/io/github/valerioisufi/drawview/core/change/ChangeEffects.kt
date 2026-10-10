package io.github.valerioisufi.drawview.core.change

import io.github.valerioisufi.drawview.core.model.DrawDocument
import io.github.valerioisufi.drawview.core.model.DrawElement
import io.github.valerioisufi.drawview.core.model.DrawPage
import io.github.valerioisufi.drawview.core.model.DrawResource
import io.github.valerioisufi.drawview.model.geometry.Rect
import kotlin.math.max
import kotlin.math.min

/**
 * What a change did to the document, recorded while it is applied: the vocabulary of the
 * observers of a [DocumentSession] (persistence, rendering, selection), which therefore do not
 * depend on the kinds of change.
 */
class ChangeEffects internal constructor(private val document: DrawDocument) {
    private val _savedElements = LinkedHashMap<DrawElement, DrawPage>()
    private val _deletedElements = LinkedHashSet<DrawElement>()
    private val _insertedPages = LinkedHashSet<DrawPage>()
    private val _savedPages = LinkedHashSet<DrawPage>()
    private val _removedPages = LinkedHashSet<DrawPage>()
    private val _versionedPages = LinkedHashSet<DrawPage>()
    private val _lostVersions = mutableListOf<Pair<DrawPage, TextData>>()
    private val _resources = mutableListOf<DrawResource>()
    private val _dirtyRegions = LinkedHashMap<DrawPage, Rect?>()

    /**
     * Elements inserted, changed or removed (removed elements are kept, hidden: see
     * [DrawPage.removedElements]), with the page holding them.
     */
    val savedElements: Map<DrawElement, DrawPage> get() = _savedElements

    /** Elements removed from their page (hidden), among the [savedElements]. */
    val deletedElements: Set<DrawElement> get() = _deletedElements

    /** Pages inserted (or put back), with their content. */
    val insertedPages: Set<DrawPage> get() = _insertedPages

    /** Pages changed: moved, formatted, removed again... (their content excepted). */
    val savedPages: Set<DrawPage> get() = _savedPages

    /** Pages removed (kept, hidden: see [DrawDocument.removedPages]). */
    val removedPages: Set<DrawPage> get() = _removedPages

    /** Pages whose content changed (see [DrawPage.contentVersions]): their versions to store. */
    val versionedPages: Set<DrawPage> get() = _versionedPages

    /**
     * Contents of texts made apart from the content they lost to (see `UpdateTexts`), as the
     * copies keeping them, with the page to add them to (see [DocumentSession]).
     */
    val lostVersions: List<Pair<DrawPage, TextData>> get() = _lostVersions

    /** Files added to the document. */
    val resources: List<DrawResource> get() = _resources

    /** Areas to draw again, in the millimeters of their page (`null`: the whole page). */
    val dirtyRegions: Map<DrawPage, Rect?> get() = _dirtyRegions

    var documentDefaultsChanged: Boolean = false
        private set

    /** The title of the document changed. */
    var documentRenamed: Boolean = false
        private set

    /** The pages must be laid out again (pages added, removed, moved or resized). */
    var layoutChanged: Boolean = false
        private set

    val isEmpty: Boolean
        get() = _savedElements.isEmpty() && _deletedElements.isEmpty() && _insertedPages.isEmpty() &&
            _savedPages.isEmpty() && _removedPages.isEmpty() && _versionedPages.isEmpty() && _lostVersions.isEmpty() &&
            _resources.isEmpty() && !documentDefaultsChanged && !documentRenamed

    /** [element] of [page] added or changed, shown or hidden. */
    internal fun saved(page: DrawPage, element: DrawElement) {
        _savedElements[element] = page
        if (element.removed != null) return
        _deletedElements.remove(element)
        dirty(page, page.boundsOf(element))
    }

    /** [element] hidden: it was at [bounds] of [page]. */
    internal fun deleted(page: DrawPage, element: DrawElement, bounds: Rect?) {
        _savedElements[element] = page
        _deletedElements.add(element)
        dirty(page, bounds)
    }

    internal fun pageInserted(page: DrawPage) {
        _removedPages.remove(page)
        _insertedPages.add(page)
        layoutChanged = true
    }

    internal fun pageRemoved(page: DrawPage) {
        _insertedPages.remove(page)
        _savedPages.remove(page)
        _removedPages.add(page)
        layoutChanged = true
    }

    /** [page] changed (moved, formatted, put back...): saved, laid out again when shown. */
    internal fun pageSaved(page: DrawPage) {
        _savedPages.add(page)
        if (document.isShown(page)) layoutChanged = true
    }

    /** The content of [page] changed: its versions to store (no layout). */
    internal fun pageVersioned(page: DrawPage) {
        _versionedPages.add(page)
    }

    /** A content of a text lost to another one made apart: [copy] keeps it on [page]. */
    internal fun versionLost(page: DrawPage, copy: TextData) {
        _lostVersions.add(page to copy)
    }

    internal fun pageFormatted(page: DrawPage) {
        pageSaved(page)
        dirty(page, null)
    }

    internal fun documentDefaultsChanged() {
        documentDefaultsChanged = true
    }

    internal fun documentRenamed() {
        documentRenamed = true
    }

    internal fun resourceAdded(resource: DrawResource) {
        _resources.add(resource)
    }

    /** Marks [rect] of [page] (the whole page when `null`) to be drawn again (when it is shown). */
    internal fun dirty(page: DrawPage, rect: Rect?) {
        page.markContentChanged()
        if (!document.isShown(page)) return
        if (_dirtyRegions.containsKey(page)) {
            val previous = _dirtyRegions[page] ?: return
            _dirtyRegions[page] = rect?.let { union(previous, it) }
        } else {
            _dirtyRegions[page] = rect
        }
    }

    private fun union(a: Rect, b: Rect) = Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))
}
