package io.github.valerioisufi.pageboard.model.change

import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.model.PageElement
import io.github.valerioisufi.pageboard.model.Page
import io.github.valerioisufi.pageboard.model.DocumentResource
import io.github.valerioisufi.pageboard.model.geometry.Rect
import kotlin.math.max
import kotlin.math.min

/**
 * What a change did to the document, recorded while it is applied: the vocabulary of the
 * observers of a [DocumentSession] (persistence, rendering, selection), which therefore do not
 * depend on the kinds of change.
 */
public class ChangeEffects internal constructor(private val document: Document) {
    private val _savedElements = LinkedHashMap<PageElement, Page>()
    private val _deletedElements = LinkedHashSet<PageElement>()
    private val _insertedPages = LinkedHashSet<Page>()
    private val _savedPages = LinkedHashSet<Page>()
    private val _removedPages = LinkedHashSet<Page>()
    private val _versionedPages = LinkedHashSet<Page>()
    private val _lostVersions = mutableListOf<Pair<Page, TextData>>()
    private val _resources = mutableListOf<DocumentResource>()
    private val _dirtyRegions = LinkedHashMap<Page, Rect?>()

    /**
     * Elements inserted, changed or removed (removed elements are kept, hidden: see
     * [Page.removedElements]), with the page holding them.
     */
    public val savedElements: Map<PageElement, Page> get() = _savedElements

    /** Elements removed from their page (hidden), among the [savedElements]. */
    public val deletedElements: Set<PageElement> get() = _deletedElements

    /** Pages inserted (or put back), with their content. */
    internal val insertedPages: Set<Page> get() = _insertedPages

    /** Pages changed: moved, formatted, removed again... (their content excepted). */
    internal val savedPages: Set<Page> get() = _savedPages

    /** Pages removed (kept, hidden: see [Document.removedPages]). */
    public val removedPages: Set<Page> get() = _removedPages

    /** Pages whose content changed (see [Page.contentVersions]): their versions to store. */
    internal val versionedPages: Set<Page> get() = _versionedPages

    /**
     * Contents of texts made apart from the content they lost to (see `UpdateTexts`), as the
     * copies keeping them, with the page to add them to (see [DocumentSession]).
     */
    public val lostVersions: List<Pair<Page, TextData>> get() = _lostVersions

    /** Files added to the document. */
    public val resources: List<DocumentResource> get() = _resources

    /** Areas to draw again, in the millimeters of their page (`null`: the whole page). */
    public val dirtyRegions: Map<Page, Rect?> get() = _dirtyRegions

    internal var documentDefaultsChanged: Boolean = false
        private set

    /** The title of the document changed. */
    internal var documentRenamed: Boolean = false
        private set

    /** The pages must be laid out again (pages added, removed, moved or resized). */
    public var layoutChanged: Boolean = false
        private set

    public val isEmpty: Boolean
        get() = _savedElements.isEmpty() && _deletedElements.isEmpty() && _insertedPages.isEmpty() &&
            _savedPages.isEmpty() && _removedPages.isEmpty() && _versionedPages.isEmpty() && _lostVersions.isEmpty() &&
            _resources.isEmpty() && !documentDefaultsChanged && !documentRenamed

    /** [element] of [page] added or changed, shown or hidden. */
    internal fun saved(page: Page, element: PageElement) {
        _savedElements[element] = page
        if (element.removed != null) return
        _deletedElements.remove(element)
        dirty(page, page.boundsOf(element))
    }

    /** [element] hidden: it was at [bounds] of [page]. */
    internal fun deleted(page: Page, element: PageElement, bounds: Rect?) {
        _savedElements[element] = page
        _deletedElements.add(element)
        dirty(page, bounds)
    }

    internal fun pageInserted(page: Page) {
        _removedPages.remove(page)
        _insertedPages.add(page)
        layoutChanged = true
    }

    internal fun pageRemoved(page: Page) {
        _insertedPages.remove(page)
        _savedPages.remove(page)
        _removedPages.add(page)
        layoutChanged = true
    }

    /** [page] changed (moved, formatted, put back...): saved, laid out again when shown. */
    internal fun pageSaved(page: Page) {
        _savedPages.add(page)
        if (document.isShown(page)) layoutChanged = true
    }

    /** The content of [page] changed: its versions to store (no layout). */
    internal fun pageVersioned(page: Page) {
        _versionedPages.add(page)
    }

    /** A content of a text lost to another one made apart: [copy] keeps it on [page]. */
    internal fun versionLost(page: Page, copy: TextData) {
        _lostVersions.add(page to copy)
    }

    internal fun pageFormatted(page: Page) {
        pageSaved(page)
        dirty(page, null)
    }

    internal fun documentDefaultsChanged() {
        documentDefaultsChanged = true
    }

    internal fun documentRenamed() {
        documentRenamed = true
    }

    internal fun resourceAdded(resource: DocumentResource) {
        _resources.add(resource)
    }

    /** Marks [rect] of [page] (the whole page when `null`) to be drawn again (when it is shown). */
    internal fun dirty(page: Page, rect: Rect?) {
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
