package io.github.valerioisufi.pageboard.agent

import io.github.valerioisufi.pageboard.model.DrawCustomElement
import io.github.valerioisufi.pageboard.model.DrawDocument
import io.github.valerioisufi.pageboard.model.DrawElement
import io.github.valerioisufi.pageboard.model.DrawImage
import io.github.valerioisufi.pageboard.model.DrawPage
import io.github.valerioisufi.pageboard.model.DrawResource
import io.github.valerioisufi.pageboard.model.DrawStroke
import io.github.valerioisufi.pageboard.model.DrawText
import io.github.valerioisufi.pageboard.model.StrokePoint
import io.github.valerioisufi.pageboard.model.StrokeTool
import io.github.valerioisufi.pageboard.model.TextAlignment
import io.github.valerioisufi.pageboard.model.change.AddElements
import io.github.valerioisufi.pageboard.model.change.AddResource
import io.github.valerioisufi.pageboard.model.change.BoxPlacement
import io.github.valerioisufi.pageboard.model.change.DocumentChange
import io.github.valerioisufi.pageboard.model.change.ImageData
import io.github.valerioisufi.pageboard.model.change.Placement
import io.github.valerioisufi.pageboard.model.change.StackMove
import io.github.valerioisufi.pageboard.model.change.StrokeData
import io.github.valerioisufi.pageboard.model.change.StrokePlacement
import io.github.valerioisufi.pageboard.model.change.TextData
import io.github.valerioisufi.pageboard.model.change.TextPlacement
import io.github.valerioisufi.pageboard.model.change.UpdateTexts
import io.github.valerioisufi.pageboard.model.change.toData
import io.github.valerioisufi.pageboard.model.geometry.Matrix2D
import io.github.valerioisufi.pageboard.model.geometry.Rect
import io.github.valerioisufi.pageboard.model.newElementId
import io.github.valerioisufi.pageboard.model.change.PlaceElements as PlaceChange
import io.github.valerioisufi.pageboard.model.change.RemoveElements as RemoveChange
import io.github.valerioisufi.pageboard.model.change.ReorderElements as ReorderChange

/**
 * The items of a proposal on the elements of a page (Information Expert): what they refer to, whether
 * it is still as it was read, and the changes they become. See `docs/plans/agent-full-access.md`.
 */
internal object ElementItems {
    /** The tools a model draws with: not the eraser or the lasso, which are not strokes on the page. */
    val DRAWING_TOOLS: Map<String, StrokeTool> = listOf(StrokeTool.PRESSURE_PEN, StrokeTool.MARKER, StrokeTool.HIGHLIGHTER, StrokeTool.DASHED_LINE)
        .associateBy { it.name.lowercase() }

    const val DEFAULT_STROKE_WIDTH: Float = 0.5f
    const val MIN_SCALE: Float = 0.05f
    const val MAX_SCALE: Float = 20f
    const val MAX_STROKE_WIDTH: Float = 50f
    const val MAX_FONT_SIZE: Float = 400f

    /** `#RRGGBB` or `#RRGGBBAA` as an ARGB color, or `null`. */
    fun parseColor(text: String): Int? {
        if (!text.startsWith("#") || (text.length != 7 && text.length != 9)) return null
        val digits = text.substring(1)
        if (digits.any { it.digitToIntOrNull(16) == null }) return null
        val rgb = digits.substring(0, 6).toInt(16)
        val alpha = if (digits.length == 8) digits.substring(6, 8).toInt(16) else 0xFF
        return (alpha shl 24) or rgb
    }

    fun parseAlignment(text: String): TextAlignment? = TextAlignment.entries.firstOrNull { it.name.lowercase() == text }

    /** The elements [refs] shown on [page], or `null` when one is not there. */
    fun elementsOf(page: DrawPage, refs: List<ElementRef>): List<DrawElement>? {
        val shown = page.elements().filter { it.removed == null }.associateBy { it.id }
        return refs.map { shown[it.id] ?: return null }
    }

    /** Whether one of [refs] changed since it was read, or is gone. */
    fun isStale(page: DrawPage?, refs: List<ElementRef>): Boolean {
        page ?: return true
        val elements = elementsOf(page, refs) ?: return true
        return elements.zip(refs).any { (element, ref) -> element.elementBase() != ref.base }
    }

    /** The stroke [id] shown in [document] and its page, or `null`. */
    fun findStroke(document: DrawDocument, id: String): Pair<DrawPage, DrawStroke>? {
        for (page in document.pages) {
            val stroke = page.strokes.firstOrNull { it.id == id && it.removed == null }
            if (stroke != null) return page to stroke
        }
        return null
    }

    /**
     * The moves of [item] as a matrix: scaled around the corner of what it moves, turned around the
     * center of what is scaled, then moved.
     */
    fun matrixOf(page: DrawPage, elements: List<DrawElement>, item: ProposalItem.PlaceElements): Matrix2D? {
        val box = elements.mapNotNull { page.boundsOf(it) }.reduceOrNull { all, next -> all.union(next) } ?: return null
        val matrix = Matrix2D().postScale(item.scale, item.scale, box.left, box.top)
        if (item.rotation != 0f) matrix.postRotate(item.rotation, box.left + box.width * item.scale / 2f, box.top + box.height * item.scale / 2f)
        return matrix.postTranslate(item.dx, item.dy)
    }

    /** Where the elements of [item] would be: the box holding them all, or `null`. */
    fun placedBox(page: DrawPage, elements: List<DrawElement>, item: ProposalItem.PlaceElements): Rect? {
        val matrix = matrixOf(page, elements, item) ?: return null
        return elements.mapNotNull { page.boundsOf(it) }.map { matrix.mapRect(it) }.reduceOrNull { all, next -> all.union(next) }
    }

    /**
     * The placements of [elements] moved by [matrix], as the editor places a selection it moves;
     * [rotation]: the degrees [matrix] turns them by, added to the rotation of each box.
     */
    fun placements(elements: List<DrawElement>, matrix: Matrix2D, rotation: Float = 0f): List<Placement> {
        val scale = matrix.scaleFactor
        return elements.mapNotNull { element ->
            when (element) {
                is DrawStroke -> StrokePlacement(element.id, element.transform.then(matrix))
                is DrawText -> {
                    val c = matrix.mapPoint(element.centerX, element.centerY)
                    val turned = normalized(element.rotation + rotation)
                    val corner = DrawText.cornerFor(c.x, c.y, element.boxWidth * scale, element.height * scale, turned, element.boxLeft * scale)
                    TextPlacement(element.id, corner.x, corner.y, element.width * scale, turned, element.fontSize * scale, element.autoWidth)
                }
                is DrawImage -> box(element.id, element.centerX, element.centerY, element.width, element.height, normalized(element.rotation + rotation), matrix)
                is DrawCustomElement -> box(element.id, element.centerX, element.centerY, element.width, element.height, normalized(element.rotation + rotation), matrix)
                else -> null
            }
        }
    }

    /** [degrees] between -180 (excluded) and 180. */
    fun normalized(degrees: Float): Float {
        val turned = degrees % 360f
        return when {
            turned > 180f -> turned - 360f
            turned <= -180f -> turned + 360f
            else -> turned
        }
    }

    private fun box(id: String, centerX: Float, centerY: Float, width: Float, height: Float, rotation: Float, matrix: Matrix2D): BoxPlacement {
        val c = matrix.mapPoint(centerX, centerY)
        val scale = matrix.scaleFactor
        return BoxPlacement(id, c.x - width * scale / 2f, c.y - height * scale / 2f, width * scale, height * scale, rotation)
    }

    /** The changes of an item on elements, applied to [document] as it is now (not stale). */
    fun changesOf(document: DrawDocument, item: ProposalItem): List<DocumentChange> = when (item) {
        is ProposalItem.RemoveElements -> listOf(RemoveChange(item.page, item.elements.map { it.id }))
        is ProposalItem.PlaceElements -> {
            val page = document.pageById(item.page)!!
            val elements = elementsOf(page, item.elements)!!
            val matrix = matrixOf(page, elements, item)
            if (matrix == null) emptyList() else listOf(PlaceChange(page.id, placements(elements, matrix, item.rotation), item.toPage ?: page.id))
        }
        is ProposalItem.ReorderElements -> {
            val page = document.pageById(item.page)!!
            val move = if (item.to == StackPlace.FRONT) StackMove.FRONT else StackMove.BACK
            listOfNotNull(ReorderChange.of(page, item.elements.map { it.id }, move))
        }
        is ProposalItem.RestyleText -> {
            val (page, text) = document.findText(item.text)!!
            listOf(UpdateTexts(page.id, listOf(restyled(text, item))))
        }
        is ProposalItem.UpdateText -> {
            val (page, text) = document.findText(item.text)!!
            listOf(UpdateTexts(page.id, listOf(updated(text, item))))
        }
        is ProposalItem.AddStroke -> {
            val data = StrokeData(
                id = newElementId(),
                points = item.points.mapIndexed { index, point -> StrokePoint(point[0], point[1], pressure = 0.5f, timestampMs = index * 8L) },
                tool = DRAWING_TOOLS.getValue(item.tool ?: "pressure_pen"),
                size = item.width ?: DEFAULT_STROKE_WIDTH,
                color = item.color?.let { parseColor(it) } ?: AGENT_TEXT_COLOR
            )
            listOf(AddElements(item.page, listOf(data)))
        }
        is ProposalItem.RestyleStroke -> {
            val (page, stroke) = findStroke(document, item.stroke)!!
            val data = stroke.toData()
            // The width asked is the one drawn: the stroke may be scaled by its placement
            val scale = stroke.transform.toMatrix().scaleFactor.takeIf { it > 0f } ?: 1f
            val copy = data.copy(
                id = newElementId(),
                color = item.color?.let { parseColor(it) } ?: data.color,
                size = item.width?.let { it / scale } ?: data.size
            )
            listOf(RemoveChange(page.id, listOf(stroke.id)), AddElements(page.id, listOf(copy)))
        }
        is ProposalItem.AddImage -> {
            // Its box was found when it was proposed (see DocumentTools)
            val box = item.area!!.rect
            val pageId = item.page ?: document.pages.last().id
            val resourceId = newElementId()
            listOf(
                AddResource.of(resourceId, DrawResource.ResourceType.IMAGE, item.path, item.hash),
                AddElements(pageId, listOf(ImageData(newElementId(), resourceId, box.left, box.top, box.width, box.height)))
            )
        }
        else -> emptyList()
    }

    /** [text] with the style of [item]. */
    fun restyled(text: DrawText, item: ProposalItem.RestyleText): TextData {
        val data = text.toData()
        return data.copy(
            color = item.color?.let { parseColor(it) } ?: data.color,
            fontSize = item.fontSize ?: data.fontSize,
            alignment = item.alignment?.let { parseAlignment(it) } ?: data.alignment
        )
    }

    /** [text] as [item] leaves it: a width given is the one its lines wrap on, whatever its text. */
    fun updated(text: DrawText, item: ProposalItem.UpdateText): TextData {
        val data = text.toData()
        return data.copy(
            text = item.markdown ?: data.text,
            color = item.color?.let { parseColor(it) } ?: data.color,
            fontSize = item.fontSize ?: data.fontSize,
            alignment = item.alignment?.let { parseAlignment(it) } ?: data.alignment,
            width = item.width ?: data.width,
            autoWidth = if (item.width != null) false else item.autoWidth ?: data.autoWidth,
            x = item.x ?: data.x,
            y = item.y ?: data.y,
            rotation = item.rotation?.let { normalized(it) } ?: data.rotation
        )
    }

    /** The ids of the elements [item] changes, to change each one by one item only. */
    fun targetsOf(item: ProposalItem): List<String> = when (item) {
        is ProposalItem.ReplaceText -> listOf(item.text)
        is ProposalItem.RemoveText -> listOf(item.text)
        is ProposalItem.RestyleText -> listOf(item.text)
        is ProposalItem.UpdateText -> listOf(item.text)
        is ProposalItem.RestyleStroke -> listOf(item.stroke)
        is ProposalItem.UpdateShape -> listOf(item.shape)
        is ProposalItem.RestyleMarkups -> item.markups.map { it.id }
        is ProposalItem.RemoveElements -> item.elements.map { it.id }
        is ProposalItem.PlaceElements -> item.elements.map { it.id }
        is ProposalItem.ReorderElements -> item.elements.map { it.id }
        else -> emptyList()
    }
}
