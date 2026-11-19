package io.github.valerioisufi.pageboard.agent

import io.github.valerioisufi.pageboard.model.DrawCustomElement
import io.github.valerioisufi.pageboard.model.DrawDocument
import io.github.valerioisufi.pageboard.model.DrawPage
import io.github.valerioisufi.pageboard.model.change.AddElements
import io.github.valerioisufi.pageboard.model.change.BoxPlacement
import io.github.valerioisufi.pageboard.model.change.CustomElementData
import io.github.valerioisufi.pageboard.model.change.DocumentChange
import io.github.valerioisufi.pageboard.model.change.PlaceElements
import io.github.valerioisufi.pageboard.model.change.UpdateElementContent
import io.github.valerioisufi.pageboard.model.decode
import io.github.valerioisufi.pageboard.model.encode
import io.github.valerioisufi.pageboard.model.geometry.Point
import io.github.valerioisufi.pageboard.model.geometry.Rect
import io.github.valerioisufi.pageboard.model.newElementId
import io.github.valerioisufi.pageboard.model.shape.ShapeBox
import io.github.valerioisufi.pageboard.model.shape.ShapeDash
import io.github.valerioisufi.pageboard.model.shape.ShapeElement
import io.github.valerioisufi.pageboard.model.shape.ShapeElementType
import io.github.valerioisufi.pageboard.model.shape.ShapeGeometry
import io.github.valerioisufi.pageboard.model.shape.ShapeHead
import io.github.valerioisufi.pageboard.model.shape.ShapeKind
import kotlin.math.abs

/**
 * The items of a proposal on the shapes of the shapes tool (Information Expert): a shape of a model
 * turned into a [ShapeElement] and its box, its style into an [UpdateElementContent]. A model never
 * writes the value of [ShapeElementType]: it names a kind, a color, the ends of a line (see
 * `docs/plans/agent-coverage.md`).
 *
 * The ends of a line are those `read_page` gives: an arrow goes from its tail ([Ends.from]) to its
 * head ([Ends.to]), whichever end of the box its head is stored at.
 */
internal object ShapeItems {
    /** The kinds a model names, and the kind of shape each one is. */
    val KINDS: Map<String, ShapeKind> = mapOf("line" to ShapeKind.LINE, "arrow" to ShapeKind.LINE, "double_arrow" to ShapeKind.LINE) +
        ShapeKind.entries.filter { !it.isLine }.associateBy { it.name.lowercase() }

    val HEADS: Map<String, ShapeHead> = ShapeHead.entries.associateBy { it.name.lowercase() }
    val OUTLINES: Map<String, ShapeDash> = ShapeDash.entries.associateBy { it.name.lowercase() }

    const val DEFAULT_WIDTH_MM: Float = 0.5f
    const val MIN_SIDE_MM: Float = 1f

    /** The ends of a line and their heads, as a model reads them. */
    data class Ends(val from: Point, val to: Point, val headFrom: ShapeHead, val headTo: ShapeHead)

    /** The box of [element] as a shape. */
    fun boxOf(element: DrawCustomElement): ShapeBox = ShapeBox(element.x, element.y, element.width, element.height, element.rotation)

    /** The ends of the line [value] in its [box], as a model reads them. */
    fun endsOf(box: ShapeBox, value: ShapeElement): Ends {
        val (start, end) = ShapeGeometry.lineEnds(box)
        // An arrow is read from its tail to its head
        return if (value.startHead != ShapeHead.NONE && value.endHead == ShapeHead.NONE) Ends(end, start, ShapeHead.NONE, value.startHead)
        else Ends(start, end, value.startHead, value.endHead)
    }

    /** The heads a line of the kind named [kind] has by default: at its tail, at its head. */
    private fun headsOf(kind: String): Pair<ShapeHead, ShapeHead> = when (kind) {
        "arrow" -> ShapeHead.NONE to ShapeHead.FILLED
        "double_arrow" -> ShapeHead.FILLED to ShapeHead.FILLED
        else -> ShapeHead.NONE to ShapeHead.NONE
    }

    /** The shape [item] adds and its box. */
    fun added(item: ProposalItem.AddShape): Pair<ShapeElement, ShapeBox> {
        val kind = KINDS.getValue(item.kind)
        val width = item.widthMm ?: DEFAULT_WIDTH_MM
        val stroke = item.color?.let { ElementItems.parseColor(it) } ?: AGENT_TEXT_COLOR
        val dash = item.outline?.let { OUTLINES.getValue(it) } ?: ShapeDash.SOLID
        if (!kind.isLine) {
            val value = ShapeElement(kind, stroke, width, dash, fill = item.fill?.let { ElementItems.parseColor(it) })
            return value to ShapeBox(item.x!!, item.y!!, item.width!!, item.height!!, ElementItems.normalized(item.rotation ?: 0f))
        }
        val (tail, head) = headsOf(item.kind)
        val value = ShapeElement(
            kind, stroke, width, dash,
            startHead = item.startHead?.let { HEADS.getValue(it) } ?: tail,
            endHead = item.endHead?.let { HEADS.getValue(it) } ?: head
        )
        return value to ShapeGeometry.lineBox(point(item.from!!), point(item.to!!), value)
    }

    /** The shape [element] is, and the box [item] leaves it in. */
    fun updated(element: DrawCustomElement, value: ShapeElement, item: ProposalItem.UpdateShape): Pair<ShapeElement, ShapeBox> {
        val stroke = item.color?.let { ElementItems.parseColor(it) } ?: value.stroke
        val width = item.widthMm ?: value.widthMm
        val dash = item.outline?.let { OUTLINES.getValue(it) } ?: value.dash
        val box = boxOf(element)
        if (!value.kind.isLine) {
            val kind = item.kind?.let { KINDS.getValue(it) } ?: value.kind
            val fill = when (item.fill) {
                null -> value.fill
                "none" -> null
                else -> ElementItems.parseColor(item.fill)
            }
            return ShapeElement(kind, stroke, width, dash, fill = fill) to box
        }
        val ends = endsOf(box, value)
        val (tail, head) = item.kind?.let { headsOf(it) } ?: (ends.headFrom to ends.headTo)
        val shaped = ShapeElement(
            ShapeKind.LINE, stroke, width, dash,
            startHead = item.startHead?.let { HEADS.getValue(it) } ?: tail,
            endHead = item.endHead?.let { HEADS.getValue(it) } ?: head
        )
        // Stored from its tail to its head: the box follows its ends and the room its heads need
        return shaped to ShapeGeometry.lineBox(item.from?.let(::point) ?: ends.from, item.to?.let(::point) ?: ends.to, shaped)
    }

    /** The changes of [item], applied to [document] as it is now (not stale). */
    fun changesOf(document: DrawDocument, item: ProposalItem): List<DocumentChange> = when (item) {
        is ProposalItem.AddShape -> {
            val (value, box) = added(item)
            val data = CustomElementData(newElementId(), ShapeElementType.id, ShapeElementType.version, ShapeElementType.encode(value), box.x, box.y, box.width, box.height, box.rotation)
            listOf(AddElements(item.page, listOf(data)))
        }
        is ProposalItem.UpdateShape -> {
            val (page, element, value) = findShape(document, item.shape)!!
            val (shaped, box) = updated(element, value, item)
            buildList {
                add(UpdateElementContent(page.id, element.id, ShapeElementType.version, ShapeElementType.encode(shaped)))
                if (box != boxOf(element)) add(PlaceElements(page.id, listOf(BoxPlacement(element.id, box.x, box.y, box.width, box.height, box.rotation))))
            }
        }
        else -> emptyList()
    }

    /** The shape [id] shown in [document]: its page, its element and its value; `null` when it is not one. */
    fun findShape(document: DrawDocument, id: String): Triple<DrawPage, DrawCustomElement, ShapeElement>? {
        for (page in document.pages) {
            val element = page.customElements.firstOrNull { it.id == id && it.removed == null } ?: continue
            val value = ShapeElementType.decode(element) ?: return null
            return Triple(page, element, value)
        }
        return null
    }

    /** The box holding [box] turned, on the page. */
    fun boundsOf(box: ShapeBox): Rect {
        val corners = listOf(Point(0f, 0f), Point(box.width, 0f), Point(box.width, box.height), Point(0f, box.height)).map { box.toPage(it) }
        return Rect(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
    }

    /** Whether the ends of a line are apart enough to draw it. */
    fun isLong(from: List<Float>, to: List<Float>): Boolean = abs(from[0] - to[0]) + abs(from[1] - to[1]) >= MIN_SIDE_MM

    private fun point(xy: List<Float>): Point = Point(xy[0], xy[1])
}
