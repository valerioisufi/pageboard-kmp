package io.github.valerioisufi.drawview.model.change

import io.github.valerioisufi.drawview.model.DrawCustomElement
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawElement
import io.github.valerioisufi.drawview.model.DrawImage
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.DrawPdf
import io.github.valerioisufi.drawview.model.DrawResource
import io.github.valerioisufi.drawview.model.DrawStroke
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.DrawTextMarkup
import io.github.valerioisufi.drawview.model.ElementOrder
import io.github.valerioisufi.drawview.model.InputToolType
import io.github.valerioisufi.drawview.model.KotlinStrokeModeller
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.StrokeMesh
import io.github.valerioisufi.drawview.model.StrokeModeller
import io.github.valerioisufi.drawview.model.StrokePoint
import io.github.valerioisufi.drawview.model.StrokeTool
import io.github.valerioisufi.drawview.model.TextAlignment
import io.github.valerioisufi.drawview.model.TextMarkupKind
import io.github.valerioisufi.drawview.model.Versions
import io.github.valerioisufi.drawview.model.geometry.AffineTransform
import io.github.valerioisufi.drawview.model.geometry.Rect
import io.github.valerioisufi.drawview.model.remodelingBrush
import io.github.valerioisufi.drawview.model.text.TextSizes
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The content of a document as plain values: what the changes carry (and what is stored and
 * exchanged), while the model objects (DrawStroke, DrawText, ...) are the live, mutable state of
 * an open document.
 */

/**
 * An element of a page as plain values (see [DrawElement]).
 *
 * [order] is its stacking order on its page; `null` for a new element, which gets the order of
 * the change adding it (see [AddElements]).
 */
@Serializable
public sealed interface ElementData {
    public val id: String
    public val order: ElementOrder?

    /**
     * A new model element with this content (and [order], or none yet), its stroke (if any)
     * modeled by [modeller].
     */
    public fun toElement(modeller: StrokeModeller = KotlinStrokeModeller): DrawElement
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
    override val order: ElementOrder? = null
) : ElementData {
    /** The stroke, modeled again from its inputs by [modeller]. */
    override fun toElement(modeller: StrokeModeller): DrawStroke {
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
        return DrawStroke(id = id, mesh = mesh, brushConfig = brush, transform = transform).withOrder(order)
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
    /** Whether its width follows its text (see `DrawText.autoWidth`): part of its placement. */
    val autoWidth: Boolean = false,
    val x: Float,
    val y: Float,
    val width: Float,
    val rotation: Float = 0f,
    val color: Int,
    val fontSize: Float,
    override val order: ElementOrder? = null,
    val versions: Versions = Versions.NONE
) : ElementData {
    override fun toElement(modeller: StrokeModeller): DrawText =
        DrawText(id).also { applyTo(it); it.height = estimatedHeight() }.withOrder(order)

    /** The height of its lines guessed without laying them out (see `DrawText.height`). */
    public fun estimatedHeight(): Float = TextSizes.estimatedHeightMm(text, fontSize)

    /** Whether its content (the text and its format) is the one of [other]. */
    internal fun hasContentOf(other: TextData): Boolean =
        text == other.text && alignment == other.alignment && color == other.color

    /** Writes this content into [text] (its identifier and its order excepted). */
    public fun applyTo(text: DrawText) {
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
        text.markDirty()
    }
}

/** An image ([DrawImage]) as plain values: the file of its resource, its rotated box (page millimeters). */
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
    override val order: ElementOrder? = null
) : ElementData {
    override fun toElement(modeller: StrokeModeller): DrawImage = DrawImage(id).withOrder(order).also {
        it.resourceId = resourceId
        it.x = x
        it.y = y
        it.width = width
        it.height = height
        it.rotation = rotation
    }
}

/** A page of a PDF ([DrawPdf]) as plain values: the file of its resource and the index of the page in it. */
@Serializable
@SerialName("pdf")
public data class PdfData(
    override val id: String,
    val resourceId: String,
    val pdfPageIndex: Int,
    override val order: ElementOrder? = null
) : ElementData {
    override fun toElement(modeller: StrokeModeller): DrawPdf = DrawPdf(pdfPageIndex, id).withOrder(order).also { it.resourceId = resourceId }
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
    override val order: ElementOrder? = null
) : ElementData {
    override fun toElement(modeller: StrokeModeller): DrawTextMarkup = DrawTextMarkup(
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
    ).withOrder(order)
}

/**
 * An element of a kind added by an application (see [DrawCustomElement]): its type, the format
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
    override val order: ElementOrder? = null
) : ElementData {
    override fun toElement(modeller: StrokeModeller): DrawCustomElement = DrawCustomElement(type, id).withOrder(order).also {
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
        order: ElementOrder? = this.order
    ): CustomElementData = CustomElementData(id, type, version, payload, x, y, width, height, rotation, order)

    override fun equals(other: Any?): Boolean = other is CustomElementData && id == other.id && type == other.type &&
        version == other.version && payload.contentEquals(other.payload) && x == other.x && y == other.y &&
        width == other.width && height == other.height && rotation == other.rotation && order == other.order

    override fun hashCode(): Int = id.hashCode() * 31 + payload.contentHashCode()

    override fun toString(): String = "CustomElementData(id=$id, type=$type, version=$version, ${payload.size} bytes)"
}

private fun <T : DrawElement> T.withOrder(order: ElementOrder?): T = apply { this.order = order ?: ElementOrder.UNASSIGNED }

/** The order of [this] as data: `null` when it has none yet. */
private val DrawElement.dataOrder: ElementOrder? get() = order.takeIf { it != ElementOrder.UNASSIGNED }

/**
 * A page with its content ([background]: `null` when the page follows the default background
 * of the document; [orderKey]: its position among the pages, see [DrawPage.orderKey]).
 */
@Serializable
public data class PageData(
    val id: String,
    val orderKey: String,
    val widthMm: Float,
    val heightMm: Float,
    val background: PageBackground? = null,
    val elements: List<ElementData> = emptyList()
) {
    /** A new page of [document] with this content (the elements of every kind in this order). */
    public fun toPage(document: DrawDocument): DrawPage {
        val page = DrawPage(
            widthMm = widthMm,
            heightMm = heightMm,
            background = background ?: document.defaultBackground,
            id = id,
            orderKey = orderKey
        )
        page.hasCustomBackground = background != null
        page.load(elements.map { it.toElement(document.strokeModeller) })
        return page
    }
}

/** A file of a document (its content travels apart, see `docs/collaboration.md`). */
@Serializable
public data class ResourceData(val id: String, val type: DrawResource.ResourceType, val hash: String = "")

// ==========================================================
// From the model
// ==========================================================

/** The values of a stroke: its inputs and brush, not its mesh (see [ElementData]). */
public fun DrawStroke.toData(): StrokeData = StrokeData(
    id = id,
    points = baseMesh.points,
    tool = baseBrush.tool,
    size = baseBrush.size,
    color = baseBrush.color,
    inputToolType = baseMesh.inputToolType,
    transform = transform,
    order = dataOrder
)

/** The values of a text, with its content versions (see [ElementData]). */
public fun DrawText.toData(): TextData = TextData(
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
    order = dataOrder,
    versions = contentVersions
)

/** The values of an image (see [ElementData]). */
public fun DrawImage.toData(): ImageData = ImageData(id, resourceId, x, y, width, height, rotation, dataOrder)

/** The values of a page of a PDF (see [ElementData]). */
public fun DrawPdf.toData(): PdfData = PdfData(id, resourceId, pdfPageIndex, dataOrder)

/** The values of a mark of the text of a PDF (see [ElementData]). */
public fun DrawTextMarkup.toData(): TextMarkupData = TextMarkupData(
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
    order = dataOrder
)

/** The values of an element of an application, its payload as it is (see [ElementData]). */
public fun DrawCustomElement.toData(): CustomElementData = CustomElementData(id, type, version, payload, x, y, width, height, rotation, dataOrder)

/** The values of any element, by its kind (see [ElementData]). */
public fun DrawElement.toData(): ElementData = when (this) {
    is DrawStroke -> toData()
    is DrawText -> toData()
    is DrawImage -> toData()
    is DrawPdf -> toData()
    is DrawTextMarkup -> toData()
    is DrawCustomElement -> toData()
}

/** The page with its content, the elements of every kind in their stacking order. */
public fun DrawPage.toData(): PageData = PageData(
    id = id,
    orderKey = orderKey,
    widthMm = widthMm,
    heightMm = heightMm,
    background = if (hasCustomBackground) background else null,
    elements = elements().map { it.toData() }
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
internal fun writeContent(element: DrawElement, data: ElementData) {
    when {
        element is DrawText && data is TextData -> {
            element.text = data.text
            element.alignment = data.alignment
            element.color = data.color
            element.markDirty()
        }
        element is DrawTextMarkup && data is TextMarkupData -> {
            element.kind = data.kind
            element.color = data.color
        }
        element is DrawCustomElement && data is CustomElementData -> {
            element.version = data.version
            element.payload = data.payload
        }
        else -> Unit
    }
}
