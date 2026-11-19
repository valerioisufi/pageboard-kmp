package io.github.valerioisufi.pageboard.model.change

import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.model.PageElement
import io.github.valerioisufi.pageboard.model.Page
import io.github.valerioisufi.pageboard.model.StrokeElement
import io.github.valerioisufi.pageboard.model.StrokeMesh
import io.github.valerioisufi.pageboard.model.geometry.Rect

/*
 * What is copied in a document, detached from it, to be pasted in any document (see
 * `docs/plans/cross-document-paste.md`): the values the changes carry, never the model objects of the
 * source, which may be changed, closed or deleted meanwhile.
 */

/**
 * A resource named by what was copied, with the file of its content on this device ([path]:
 * empty for the file of another device not received yet).
 */
public class CopiedResource(public val resource: ResourceData, public val path: String)

/** What was copied in the document [sourceDocumentId], with the [resources] it names. */
public sealed class CopiedContent {
    public abstract val sourceDocumentId: String
    public abstract val resources: List<CopiedResource>

    /**
     * The meshes of the strokes copied, by the identifier of their stroke (values that never
     * change): a stroke pasted takes its mesh instead of being modeled again.
     */
    internal var strokeMeshes: Map<String, StrokeMesh> = emptyMap()
}

/**
 * Elements of the page [sourcePageId]: their values in stacking order (the bottom first), the
 * box they fill on that page ([bounds], page millimeters) and the rotation of their frame.
 */
public class CopiedElements(
    override val sourceDocumentId: String,
    public val sourcePageId: String,
    public val elements: List<ElementData>,
    public val bounds: Rect,
    public val baseRotation: Float = 0f,
    override val resources: List<CopiedResource> = emptyList()
) : CopiedContent()

/**
 * Pages with all their content, in their order: each one with the background it shows (its own,
 * or the one of its document then).
 */
public class CopiedPages(
    override val sourceDocumentId: String,
    public val pages: List<PageData>,
    override val resources: List<CopiedResource> = emptyList()
) : CopiedContent()

/** The resource [this] element names, `null` for an element of no file. */
private val ElementData.namedResource: String?
    get() = when (this) {
        is ImageData -> resourceId
        is PdfData -> resourceId
        is TextMarkupData -> resourceId
        is StrokeData, is TextData, is CustomElementData -> null
    }

/** The resources of [this] document named by [elements], with their files. */
private fun Document.resourcesOf(elements: List<ElementData>): List<CopiedResource> =
    elements.mapNotNull { it.namedResource }.distinct().mapNotNull { id ->
        findResource(id)?.let { CopiedResource(ResourceData(it.id, it.type, it.hash), it.path) }
    }

/**
 * Copies [elements] of [page] of this document, in their stacking order, filling [bounds] (page
 * millimeters) in a frame of rotation [baseRotation].
 */
public fun Document.copyElements(page: Page, elements: List<PageElement>, bounds: Rect, baseRotation: Float = 0f): CopiedElements {
    val values = elements.sortedWith(Page.stackOrder).map { it.toData() }
    return CopiedElements(id, page.id, values, bounds, baseRotation, resourcesOf(values)).also { it.strokeMeshes = meshesOf(elements) }
}

private fun meshesOf(elements: List<PageElement>): Map<String, StrokeMesh> =
    elements.filterIsInstance<StrokeElement>().associate { it.id to it.baseMesh }

/**
 * Copies [pages] of this document with all their content, each with the background it shows
 * (made its own when it follows the document, see `docs/plans/cross-document-paste.md`).
 */
public fun Document.copyPages(pages: List<Page>): CopiedPages {
    val values = pages.map { page ->
        // The stack in its order, then the PDF pages and their marks (outside of the stack)
        val stack = page.stack
        val inStack = stack.toHashSet()
        val others = page.elements().filter { it !in inStack }
        page.toData().copy(background = page.background, elements = (stack + others).map { it.toData() })
    }
    return CopiedPages(id, values, resourcesOf(values.flatMap { it.elements })).also { copied ->
        copied.strokeMeshes = meshesOf(pages.flatMap { it.strokes })
    }
}
