package io.github.valerioisufi.pageboard.model.change

import io.github.valerioisufi.pageboard.model.CustomElement
import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.model.PageElement
import io.github.valerioisufi.pageboard.model.ImageElement
import io.github.valerioisufi.pageboard.model.Page
import io.github.valerioisufi.pageboard.model.PdfElement
import io.github.valerioisufi.pageboard.model.DocumentResource
import io.github.valerioisufi.pageboard.model.StrokeElement
import io.github.valerioisufi.pageboard.model.TextElement
import io.github.valerioisufi.pageboard.model.TextMarkupElement
import io.github.valerioisufi.pageboard.model.ElementOrder
import io.github.valerioisufi.pageboard.model.InputToolType
import io.github.valerioisufi.pageboard.model.KotlinStrokeModeller
import io.github.valerioisufi.pageboard.model.PageBackground
import io.github.valerioisufi.pageboard.model.PageLayerData
import io.github.valerioisufi.pageboard.model.StrokeMesh
import io.github.valerioisufi.pageboard.model.StrokeModeller
import io.github.valerioisufi.pageboard.model.StrokePoint
import io.github.valerioisufi.pageboard.model.StrokeTool
import io.github.valerioisufi.pageboard.model.TextAlignment
import io.github.valerioisufi.pageboard.model.TextMarkupKind
import io.github.valerioisufi.pageboard.model.Versions
import io.github.valerioisufi.pageboard.model.geometry.AffineTransform
import io.github.valerioisufi.pageboard.model.geometry.Rect
import io.github.valerioisufi.pageboard.model.remodelingBrush
import io.github.valerioisufi.pageboard.model.text.TextSizes
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The content of a document as plain values: what the changes carry (and what is stored and
 * exchanged), while the model objects (StrokeElement, TextElement, ...) are the live, mutable state of
 * an open document.
 */

/**
 * An element of a page as plain values (see [PageElement]).
 *
 * [order] is its stacking order on its page; `null` for a new element, which gets the order of
 * the change adding it (see [AddElements]).
 */
@Serializable
public sealed interface ElementData {
    public val id: String
    public val order: ElementOrder?
    public val locked: Boolean get() = false
    public val layerId: String? get() = null

    /**
     * A new model element with this content (and [order], or none yet), its stroke (if any)
     * modeled by [modeller].
     */
    public fun toElement(modeller: StrokeModeller = KotlinStrokeModeller): PageElement
}

/**
 * A stroke: its inputs and brush as it was drawn, and its placement on the page.
 */
@Serializable
@SerialName("stroke")
public data class StrokeData(
    override val id: String,
    val points: List<StrokePoint>,
    val tool: StrokeTool,
    val size: Float,
    val color: Int,
    val inputToolType: InputToolType = InputToolType.UNKNOWN,
    val transform: AffineTransform = AffineTransform.IDENTITY,
    override val order: ElementOrder? = null,
    override val locked: Boolean = false,
    override val layerId: String? = null
) : ElementData {
    /** The stroke, modeled again from its inputs by [modeller]. */
    override fun toElement(modeller: StrokeModeller): StrokeElement {
        val brush = remodelingBrush(tool, size, color)
        // The inputs are kept as they are (the modeller may adjust what it records)
        val mesh = modeller.buildMesh(points, brush, inputToolType)?.withPoints(points) ?: StrokeMesh(
            positions = floatArrayOf(),
            indices = shortArrayOf(),
            color = brush.color,
            tool = brush.tool,
            size = brush.size,
            points = points,
            inputToolType = inputToolType
        )
        return StrokeElement(id = id, mesh = mesh, brushConfig = brush, transform = transform).withState(order, locked, layerId)
    }
}

/**
 * A text block. [versions]: the changes of the content this content includes (for a change, the
 * ones of the content it was made from; see `UpdateTexts`).
 */
@Serializable
@SerialName("text")
public data class TextData(
    override val id: String,
    val text: String,
    val alignment: TextAlignment = TextAlignment.START,
    /** Whether its width follows its text (see `TextElement.autoWidth`): part of its placement. */
    val autoWidth: Boolean = false,
    val x: Float,
    val y: Float,
    val width: Float,
    val rotation: Float = 0f,
    val color: Int,
    val fontSize: Float,
    /** The color of its fill, or `null` for none (see `TextElement.background`): part of its content. */
    val background: Int? = null,
    /** The text it goes on into (see `TextElement.next`): part of its content. */
    val next: String? = null,
    /** Whether its first line goes on the last line of the text before it (see `TextElement.continuesLine`). */
    val continuesLine: Boolean = false,
    /** Identifier of its custom font family, or `null` for default sans (see `TextElement.fontFamily`). */
    val fontFamily: String? = null,
    /** Identifier of the text box whose list numbering this box continues, or `null` (see `TextElement.continueNumberingFrom`). */
    val continueNumberingFrom: String? = null,
    /** Manual height limit in millimeters for a box in a chain, or `null` (see `TextElement.maxHeight`). */
    val maxHeight: Float? = null,
    override val order: ElementOrder? = null,
    val versions: Versions = Versions.NONE,
    override val locked: Boolean = false,
    override val layerId: String? = null
) : ElementData {
    override fun toElement(modeller: StrokeModeller): TextElement =
        TextElement(id).also { applyTo(it); it.height = estimatedHeight() }.withState(order, locked, layerId)

    /** The height of its lines guessed without laying them out (see `TextElement.height`). */
    public fun estimatedHeight(): Float = TextSizes.estimatedHeightMm(text, fontSize)

    /** Whether its content (the text and its format) is the one of [other]. */
    internal fun hasContentOf(other: TextData): Boolean =
        text == other.text && alignment == other.alignment && color == other.color && background == other.background &&
            next == other.next && continuesLine == other.continuesLine &&
            fontFamily == other.fontFamily && continueNumberingFrom == other.continueNumberingFrom &&
            maxHeight == other.maxHeight

    /** Writes this content into [text] (its identifier and its order excepted). */
    public fun applyTo(text: TextElement) {
        text.contentVersions = versions
        text.text = this.text
        text.alignment = alignment
        text.autoWidth = autoWidth
        text.x = x
        text.y = y
        text.width = width
        text.rotation = rotation
        text.color = color
        text.fontSize = fontSize
        text.background = background
        text.next = next
        text.continuesLine = continuesLine
        text.fontFamily = fontFamily
        text.continueNumberingFrom = continueNumberingFrom
        text.maxHeight = maxHeight
        text.markDirty()
    }
}

/** An image ([ImageElement]) as plain values: the file of its resource, its rotated box (page millimeters). */
@Serializable
@SerialName("image")
public data class ImageData(
    override val id: String,
    val resourceId: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val rotation: Float = 0f,
    override val order: ElementOrder? = null,
    override val locked: Boolean = false,
    override val layerId: String? = null
) : ElementData {
    override fun toElement(modeller: StrokeModeller): ImageElement = ImageElement(id).withState(order, locked, layerId).also {
        it.resourceId = resourceId
        it.x = x
        it.y = y
        it.width = width
        it.height = height
        it.rotation = rotation
    }
}

/** A page of a PDF ([PdfElement]) as plain values: the file of its resource and the index of the page in it. */
@Serializable
@SerialName("pdf")
public data class PdfData(
    override val id: String,
    val resourceId: String,
    val pdfPageIndex: Int,
    override val order: ElementOrder? = null,
    override val locked: Boolean = false,
    override val layerId: String? = null
) : ElementData {
    override fun toElement(modeller: StrokeModeller): PdfElement = PdfElement(pdfPageIndex, id).withState(order, locked, layerId).also { it.resourceId = resourceId }
}

/**
 * A mark of the text of a PDF ([rects]: left, top, right, bottom of each line, in points of the
 * PDF page).
 */
@Serializable
@SerialName("textMarkup")
public data class TextMarkupData(
    override val id: String,
    val resourceId: String,
    val pdfPageIndex: Int,
    val pdfWidthPt: Float,
    val pdfHeightPt: Float,
    val startIndex: Int,
    val endIndex: Int,
    val text: String,
    val rects: List<Float>,
    val kind: TextMarkupKind,
    val color: Int,
    override val order: ElementOrder? = null,
    override val locked: Boolean = false,
    override val layerId: String? = null
) : ElementData {
    override fun toElement(modeller: StrokeModeller): TextMarkupElement = TextMarkupElement(
        id = id,
        resourceId = resourceId,
        pdfPageIndex = pdfPageIndex,
        pdfWidthPt = pdfWidthPt,
        pdfHeightPt = pdfHeightPt,
        startIndex = startIndex,
        endIndex = endIndex,
        text = text,
        rects = rects.chunked(4).filter { it.size == 4 }.map { Rect(it[0], it[1], it[2], it[3]) },
        kind = kind,
        color = color
    ).withState(order, locked, layerId)
}

/**
 * An element of a kind added by an application (see [CustomElement]): its type, the format
 * and the bytes of its value, its box.
 */
@Serializable
@SerialName("custom")
public class CustomElementData(
    override val id: String,
    /** The identifier of its type (named so not to be the discriminator of the kinds of data). */
    @SerialName("elementType") public val type: String,
    public val version: Int,
    public val payload: ByteArray,
    public val x: Float,
    public val y: Float,
    public val width: Float,
    public val height: Float,
    public val rotation: Float = 0f,
    override val order: ElementOrder? = null,
    override val locked: Boolean = false,
    override val layerId: String? = null
) : ElementData {
    override fun toElement(modeller: StrokeModeller): CustomElement = CustomElement(type, id).withState(order, locked, layerId).also {
        it.version = version
        it.payload = payload
        it.x = x
        it.y = y
        it.width = width
        it.height = height
        it.rotation = rotation
    }

    public fun copy(
        id: String = this.id,
        x: Float = this.x,
        y: Float = this.y,
        order: ElementOrder? = this.order,
        locked: Boolean = this.locked,
        layerId: String? = this.layerId
    ): CustomElementData = CustomElementData(id, type, version, payload, x, y, width, height, rotation, order, locked, layerId)

    override fun equals(other: Any?): Boolean = other is CustomElementData && id == other.id && type == other.type &&
        version == other.version && payload.contentEquals(other.payload) && x == other.x && y == other.y &&
        width == other.width && height == other.height && rotation == other.rotation && order == other.order &&
        locked == other.locked && layerId == other.layerId

    override fun hashCode(): Int {
        var result = id.hashCode() * 31 + payload.contentHashCode()
        result = 31 * result + locked.hashCode()
        result = 31 * result + (layerId?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String = "CustomElementData(id=$id, type=$type, version=$version, ${payload.size} bytes)"
}

private fun <T : PageElement> T.withState(order: ElementOrder?, locked: Boolean, layerId: String?): T = apply {
    this.order = order ?: ElementOrder.UNASSIGNED
    this.locked = locked
    this.layerId = layerId
}

/** The order of [this] as data: `null` when it has none yet. */
private val PageElement.dataOrder: ElementOrder? get() = order.takeIf { it != ElementOrder.UNASSIGNED }

/**
 * A page with its content ([background]: `null` when the page follows the default background
 * of the document; [orderKey]: its position among the pages, see [Page.orderKey]).
 */
@Serializable
public data class PageData(
    val id: String,
    val orderKey: String,
    val widthMm: Float,
    val heightMm: Float,
    val background: PageBackground? = null,
    val elements: List<ElementData> = emptyList(),
    val layers: List<PageLayerData> = emptyList()
) {
    /** A new page of [document] with this content (the elements of every kind in this order). */
    public fun toPage(document: Document): Page {
        val page = Page(
            widthMm = widthMm,
            heightMm = heightMm,
            background = background ?: document.defaultBackground,
            id = id,
            orderKey = orderKey
        )
        page.hasCustomBackground = background != null
        page.loadLayers(layers.map { it.toLayer() })
        page.load(elements.map { it.toElement(document.strokeModeller) })
        return page
    }
}

/** A file of a document (its content travels apart, see `docs/plans/collaboration.md`). */
@Serializable
public data class ResourceData(val id: String, val type: DocumentResource.ResourceType, val hash: String = "")

// ==========================================================
// From the model
// ==========================================================

/** The values of a stroke: its inputs and brush, not its mesh (see [ElementData]). */
public fun StrokeElement.toData(): StrokeData = StrokeData(
    id = id,
    points = baseMesh.points,
    tool = baseBrush.tool,
    size = baseBrush.size,
    color = baseBrush.color,
    inputToolType = baseMesh.inputToolType,
    transform = transform,
    order = dataOrder,
    locked = locked,
    layerId = layerId
)

/** The values of a text, with its content versions (see [ElementData]). */
public fun TextElement.toData(): TextData = TextData(
    id = id,
    text = text,
    alignment = alignment,
    autoWidth = autoWidth,
    x = x,
    y = y,
    width = width,
    rotation = rotation,
    color = color,
    fontSize = fontSize,
    background = background,
    next = next,
    continuesLine = continuesLine,
    fontFamily = fontFamily,
    continueNumberingFrom = continueNumberingFrom,
    maxHeight = maxHeight,
    order = dataOrder,
    versions = contentVersions,
    locked = locked,
    layerId = layerId
)

/** The values of an image (see [ElementData]). */
public fun ImageElement.toData(): ImageData = ImageData(id, resourceId, x, y, width, height, rotation, dataOrder, locked, layerId)

/** The values of a page of a PDF (see [ElementData]). */
public fun PdfElement.toData(): PdfData = PdfData(id, resourceId, pdfPageIndex, dataOrder, locked, layerId)

/** The values of a mark of the text of a PDF (see [ElementData]). */
public fun TextMarkupElement.toData(): TextMarkupData = TextMarkupData(
    id = id,
    resourceId = resourceId,
    pdfPageIndex = pdfPageIndex,
    pdfWidthPt = pdfWidthPt,
    pdfHeightPt = pdfHeightPt,
    startIndex = startIndex,
    endIndex = endIndex,
    text = text,
    rects = rects.flatMap { listOf(it.left, it.top, it.right, it.bottom) },
    kind = kind,
    color = color,
    order = dataOrder,
    locked = locked,
    layerId = layerId
)

/** The values of an element of an application, its payload as it is (see [ElementData]). */
public fun CustomElement.toData(): CustomElementData = CustomElementData(id, type, version, payload, x, y, width, height, rotation, dataOrder, locked, layerId)

/** The values of any element, by its kind (see [ElementData]). */
public fun PageElement.toData(): ElementData = when (this) {
    is StrokeElement -> toData()
    is TextElement -> toData()
    is ImageElement -> toData()
    is PdfElement -> toData()
    is TextMarkupElement -> toData()
    is CustomElement -> toData()
}

/** The page with its content, the elements of every kind in their stacking order. */
public fun Page.toData(): PageData = PageData(
    id = id,
    orderKey = orderKey,
    widthMm = widthMm,
    heightMm = heightMm,
    background = if (hasCustomBackground) background else null,
    elements = elements().map { it.toData() },
    layers = layers.map { it.toData() }
)

/**
 * The placement of the element of [this] (see [Placement]), or `null` for an element not placed
 * freely.
 */
public fun ElementData.placement(): Placement? = when (this) {
    is StrokeData -> StrokePlacement(id, transform, order)
    is TextData -> TextPlacement(id, x, y, width, rotation, fontSize, autoWidth, order)
    is ImageData -> BoxPlacement(id, x, y, width, height, rotation, order = order)
    is CustomElementData -> BoxPlacement(id, x, y, width, height, rotation, order = order)
    is PdfData, is TextMarkupData -> null
}

/**
 * Writes the content of [data] (the same element) into [element]: the text and its format, the
 * style of a mark (what never changes, e.g. the inputs of a stroke, is not written).
 */
internal fun writeContent(element: PageElement, data: ElementData) {
    when {
        element is TextElement && data is TextData -> {
            element.text = data.text
            element.alignment = data.alignment
            element.color = data.color
            element.background = data.background
            element.next = data.next
            element.continuesLine = data.continuesLine
            element.fontFamily = data.fontFamily
            element.continueNumberingFrom = data.continueNumberingFrom
            element.maxHeight = data.maxHeight
            element.markDirty()
        }
        element is TextMarkupElement && data is TextMarkupData -> {
            element.kind = data.kind
            element.color = data.color
        }
        element is CustomElement && data is CustomElementData -> {
            element.version = data.version
            element.payload = data.payload
        }
        else -> Unit
    }
}
