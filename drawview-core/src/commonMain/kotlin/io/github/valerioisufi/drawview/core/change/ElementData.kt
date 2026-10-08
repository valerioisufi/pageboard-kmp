package io.github.valerioisufi.drawview.core.change

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.valerioisufi.drawview.core.engine.InkStrokeEngine
import io.github.valerioisufi.drawview.core.geometry.AffineTransform
import io.github.valerioisufi.drawview.core.model.DrawDocument
import io.github.valerioisufi.drawview.core.model.DrawElement
import io.github.valerioisufi.drawview.core.model.DrawImage
import io.github.valerioisufi.drawview.core.model.DrawPage
import io.github.valerioisufi.drawview.core.model.DrawPdf
import io.github.valerioisufi.drawview.core.model.DrawResource
import io.github.valerioisufi.drawview.core.model.DrawStroke
import io.github.valerioisufi.drawview.core.model.DrawText
import io.github.valerioisufi.drawview.core.model.DrawTextMarkup
import io.github.valerioisufi.drawview.core.model.ElementOrder
import io.github.valerioisufi.drawview.core.model.InputToolType
import io.github.valerioisufi.drawview.core.model.PageBackground
import io.github.valerioisufi.drawview.core.model.StrokeMesh
import io.github.valerioisufi.drawview.core.model.StrokePoint
import io.github.valerioisufi.drawview.core.model.StrokeTool
import io.github.valerioisufi.drawview.core.model.TextAlignment
import io.github.valerioisufi.drawview.core.model.TextMarkupKind
import io.github.valerioisufi.drawview.core.model.Versions
import io.github.valerioisufi.drawview.core.model.remodelingBrush
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
sealed interface ElementData {
    val id: String
    val order: ElementOrder?

    /** A new model element with this content (and [order], or none yet). */
    fun toElement(): DrawElement
}

/**
 * A stroke: its inputs and brush as it was drawn, and its placement on the page.
 */
@Serializable
@SerialName("stroke")
data class StrokeData(
    override val id: String,
    val points: List<StrokePoint>,
    val tool: StrokeTool,
    val size: Float,
    val color: Int,
    val inputToolType: InputToolType = InputToolType.UNKNOWN,
    val transform: AffineTransform = AffineTransform.IDENTITY,
    override val order: ElementOrder? = null
) : ElementData {
    /** The stroke, modeled again from its inputs by the stroke engine. */
    override fun toElement(): DrawStroke {
        val brush = remodelingBrush(tool, size, Color(color))
        // The inputs are kept as they are (the modeller may adjust what it records)
        val mesh = InkStrokeEngine.buildMesh(points, brush, inputToolType)?.withPoints(points) ?: StrokeMesh(
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
data class TextData(
    override val id: String,
    val text: String,
    val alignment: TextAlignment = TextAlignment.START,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val rotation: Float = 0f,
    val color: Int,
    val fontSize: Float,
    override val order: ElementOrder? = null,
    val versions: Versions = Versions.NONE
) : ElementData {
    override fun toElement(): DrawText = DrawText(id).also { applyTo(it) }.withOrder(order)

    /** Whether its content (the text and its format) is the one of [other]. */
    fun hasContentOf(other: TextData): Boolean =
        text == other.text && alignment == other.alignment && color == other.color

    /** Writes this content into [text] (its identifier and its order excepted). */
    fun applyTo(text: DrawText) {
        text.contentVersions = versions
        text.text = this.text
        text.alignment = alignment
        text.x = x
        text.y = y
        text.width = width
        text.height = height
        text.rotation = rotation
        text.color = color
        text.fontSize = fontSize
        text.markDirty()
    }
}

@Serializable
@SerialName("image")
data class ImageData(
    override val id: String,
    val resourceId: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val rotation: Float = 0f,
    override val order: ElementOrder? = null
) : ElementData {
    override fun toElement(): DrawImage = DrawImage(id).withOrder(order).also {
        it.resourceId = resourceId
        it.x = x
        it.y = y
        it.width = width
        it.height = height
        it.rotation = rotation
    }
}

@Serializable
@SerialName("pdf")
data class PdfData(
    override val id: String,
    val resourceId: String,
    val pdfPageIndex: Int,
    override val order: ElementOrder? = null
) : ElementData {
    override fun toElement(): DrawPdf = DrawPdf(pdfPageIndex, id).withOrder(order).also { it.resourceId = resourceId }
}

/**
 * A mark of the text of a PDF ([rects]: left, top, right, bottom of each line, in points of the
 * PDF page).
 */
@Serializable
@SerialName("textMarkup")
data class TextMarkupData(
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
    override fun toElement(): DrawTextMarkup = DrawTextMarkup(
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

private fun <T : DrawElement> T.withOrder(order: ElementOrder?): T = apply { this.order = order ?: ElementOrder.UNASSIGNED }

/** The order of [this] as data: `null` when it has none yet. */
private val DrawElement.dataOrder: ElementOrder? get() = order.takeIf { it != ElementOrder.UNASSIGNED }

/**
 * A page with its content ([background]: `null` when the page follows the default background
 * of the document; [orderKey]: its position among the pages, see [DrawPage.orderKey]).
 */
@Serializable
data class PageData(
    val id: String,
    val orderKey: String,
    val widthMm: Float,
    val heightMm: Float,
    val background: PageBackground? = null,
    val elements: List<ElementData> = emptyList()
) {
    /** A new page of [document] with this content (the elements of every kind in this order). */
    fun toPage(document: DrawDocument): DrawPage {
        val page = DrawPage(
            widthMm = widthMm,
            heightMm = heightMm,
            background = background ?: document.defaultBackground,
            id = id,
            orderKey = orderKey
        )
        page.hasCustomBackground = background != null
        for (data in elements) page.add(data.toElement())
        return page
    }
}

/** A file of a document (its content travels apart, see `docs/collaboration.md`). */
@Serializable
data class ResourceData(val id: String, val type: DrawResource.ResourceType, val hash: String = "")

// ==========================================================
// From the model
// ==========================================================

fun DrawStroke.toData() = StrokeData(
    id = id,
    points = baseMesh.points,
    tool = baseBrush.tool,
    size = baseBrush.size,
    color = baseBrush.color.toArgb(),
    inputToolType = baseMesh.inputToolType,
    transform = transform,
    order = dataOrder
)

fun DrawText.toData() = TextData(
    id = id,
    text = text,
    alignment = alignment,
    x = x,
    y = y,
    width = width,
    height = height,
    rotation = rotation,
    color = color,
    fontSize = fontSize,
    order = dataOrder,
    versions = contentVersions
)

fun DrawImage.toData() = ImageData(id, resourceId, x, y, width, height, rotation, dataOrder)

fun DrawPdf.toData() = PdfData(id, resourceId, pdfPageIndex, dataOrder)

fun DrawTextMarkup.toData() = TextMarkupData(
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

fun DrawElement.toData(): ElementData = when (this) {
    is DrawStroke -> toData()
    is DrawText -> toData()
    is DrawImage -> toData()
    is DrawPdf -> toData()
    is DrawTextMarkup -> toData()
}

/** The page with its content, the elements of every kind in their stacking order. */
fun DrawPage.toData(): PageData = PageData(
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
fun ElementData.placement(): Placement? = when (this) {
    is StrokeData -> StrokePlacement(id, transform, order)
    is TextData -> BoxPlacement(id, x, y, width, height, rotation, fontSize, order)
    is ImageData -> BoxPlacement(id, x, y, width, height, rotation, order = order)
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
        else -> Unit
    }
}
