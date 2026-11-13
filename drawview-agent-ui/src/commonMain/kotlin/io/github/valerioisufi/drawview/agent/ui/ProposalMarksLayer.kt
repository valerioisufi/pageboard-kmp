package io.github.valerioisufi.drawview.agent.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateSet
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.drawview.agent.PlannedMark
import io.github.valerioisufi.drawview.agent.Proposal
import io.github.valerioisufi.drawview.agent.ProposalPlan
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * What the user chose of a proposal being reviewed, shared by its list and by its marks on the page
 * (Controller): the items left out ([unchecked]) and the item looked at ([chosen]).
 */
internal class ReviewSelection {
    /** The items the user does not want applied. */
    val unchecked: SnapshotStateSet<Int> = mutableStateSetOf()

    /** The item the user chose in the list: the page goes to it, and its marks stand out. */
    var chosen: Int? by mutableStateOf(null)
}

/** How strongly a mark is drawn. */
internal enum class Emphasis {
    /** Of an item the user left out: it would not be applied. */
    FAINT,
    NORMAL,

    /** Of the item chosen in the list. */
    CHOSEN
}

/** A mark to draw on its page: how strongly, and the words drawn on a page it changes as a whole. */
internal class DrawnMark(val mark: PlannedMark, val emphasis: Emphasis, val label: String? = null)

/**
 * The marks of the proposals waiting on a document, as they are drawn: every item as its plan says,
 * faint when the user left it out, strong when it is the one chosen in the list. What goes on a page
 * the plan adds is not drawn: the page is not there yet (docs/agent-preview.md, decision 3).
 *
 * @param labels the words of an item on a page it changes as a whole (removed, moved, copied, its format).
 */
internal fun drawnMarks(
    proposals: List<Proposal>,
    plans: Map<String, ProposalPlan>,
    selections: Map<String, ReviewSelection>,
    labels: (Proposal, Int) -> String? = { _, _ -> null }
): List<DrawnMark> = proposals.filter { it.isWaiting }.flatMap { proposal ->
    val plan = plans[proposal.id] ?: return@flatMap emptyList()
    val selection = selections[proposal.id]
    plan.items.flatMapIndexed { index, item ->
        val emphasis = when {
            selection?.chosen == index -> Emphasis.CHOSEN
            selection != null && index in selection.unchecked -> Emphasis.FAINT
            else -> Emphasis.NORMAL
        }
        item.marks.filterNot { it.isOnNewPage }.map { mark ->
            val onPage = mark.kind == PlannedMark.Kind.PAGE_REMOVED || mark.kind == PlannedMark.Kind.PAGE_CHANGED
            DrawnMark(mark, emphasis, if (onPage) labels(proposal, index) else null)
        }
    }
}

/** The colors of the marks: of what is added, removed, moved, changed, and of what is stale. */
internal object MarkColors {
    val added = Color(0xFF2E7D32)
    val removed = Color(0xFFC62828)
    val moved = Color(0xFF1565C0)
    val changed = Color(0xFFB26A00)
    val stale = Color(0xFF757575)

    fun of(kind: PlannedMark.Kind): Color = when (kind) {
        PlannedMark.Kind.ADDED -> added
        PlannedMark.Kind.REMOVED, PlannedMark.Kind.PAGE_REMOVED -> removed
        PlannedMark.Kind.MOVED -> moved
        PlannedMark.Kind.CHANGED, PlannedMark.Kind.PAGE_CHANGED -> changed
        PlannedMark.Kind.STALE -> stale
    }
}

/**
 * Draws [marks] over the canvas of [controller], each one on its page where the page is on the
 * screen (Pure Fabrication): what is added in green, what is removed under a red veil, what moves
 * with a dashed frame where it is, a frame where it goes and an arrow between, what changes where it
 * is in amber, a page changed as a whole with its frame and its label.
 *
 * It only draws: it takes no pointer, so the canvas under it is used as always. The camera and the
 * pages are read while drawing, so the marks follow a pan and a zoom without being composed again.
 */
@Composable
internal fun ProposalMarksLayer(controller: DrawViewController, marks: List<DrawnMark>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier.testTag(MARKS_TAG)) {
        // Read here: a pan, a zoom or a change of the pages draws again, and composes nothing
        @Suppress("UNUSED_VARIABLE") val frame = controller.version
        @Suppress("UNUSED_VARIABLE") val camera = controller.viewport.camera.moveCount
        val pages = controller.document.pages
        val indexes = HashMap<String, Int>(pages.size)
        pages.forEachIndexed { index, page -> indexes[page.id] = index }
        val screen = Rect(Offset.Zero, size)
        val matrices = HashMap<Int, Matrix2D?>()
        for (drawn in marks) {
            val index = indexes[drawn.mark.pageId] ?: continue
            val matrix = matrices.getOrPut(index) { controller.viewport.pageMmToScreen(index) } ?: continue
            val page = matrix.mapRect(pages[index].rect()).toCompose()
            // Only the pages on the screen
            if (!page.overlaps(screen)) continue
            clipRect(page.left, page.top, page.right, page.bottom) { drawMark(drawn, matrix, page, measurer) }
        }
    }
}

/** The tag of the layer of the marks, for the tests. */
internal const val MARKS_TAG: String = "agent-marks"

private fun io.github.valerioisufi.drawview.model.geometry.Rect.toCompose(): Rect = Rect(left, top, right, bottom)

private fun DrawScope.drawMark(drawn: DrawnMark, matrix: Matrix2D, page: Rect, measurer: TextMeasurer) {
    val mark = drawn.mark
    val color = MarkColors.of(mark.kind)
    val alpha = if (drawn.emphasis == Emphasis.FAINT) FAINT_ALPHA else 1f
    val line = (if (drawn.emphasis == Emphasis.CHOSEN) CHOSEN_LINE else LINE).toPx()
    val gap = FRAME_GAP.toPx()
    val box = matrix.mapRect(mark.box).toCompose().inflate(gap)
    val corner = CornerRadius(CORNER.toPx())
    val dashes = PathEffect.dashPathEffect(floatArrayOf(DASH.toPx(), DASH.toPx()))
    fun frame(rect: Rect, dashed: Boolean = false, fill: Float = 0f) {
        if (fill > 0f) drawRoundRect(color.copy(alpha = fill * alpha), rect.topLeft, rect.size, corner)
        drawRoundRect(color.copy(alpha = alpha), rect.topLeft, rect.size, corner, style = Stroke(line, pathEffect = if (dashed) dashes else null))
    }
    val strong = drawn.emphasis == Emphasis.CHOSEN
    when (mark.kind) {
        PlannedMark.Kind.ADDED -> {
            if (mark.points.size >= 4) {
                val path = Path()
                for (i in 0 until mark.points.size / 2) {
                    val point = matrix.mapPoint(mark.points[i * 2], mark.points[i * 2 + 1])
                    if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                }
                val width = max(mark.strokeWidth * matrix.scaleFactor, 1f)
                drawPath(path, Color(mark.color).copy(alpha = Color(mark.color).alpha * alpha), style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
                frame(box, dashed = true)
            } else {
                frame(box, fill = if (strong) STRONG_FILL else FILL)
            }
        }
        PlannedMark.Kind.REMOVED -> {
            frame(box, fill = if (strong) STRONG_VEIL else VEIL)
            drawLine(color.copy(alpha = alpha), box.bottomLeft, box.topRight, line)
        }
        PlannedMark.Kind.MOVED -> {
            val to = mark.toBox?.let { matrix.mapRect(it).toCompose().inflate(gap) }
            frame(box, dashed = true)
            if (to != null) {
                frame(to, fill = if (strong) STRONG_FILL else FILL)
                drawArrow(box.center, to.center, color.copy(alpha = alpha), line)
            }
        }
        PlannedMark.Kind.CHANGED -> frame(box, fill = if (strong) STRONG_FILL else FILL)
        PlannedMark.Kind.STALE -> frame(box, dashed = true)
        PlannedMark.Kind.PAGE_REMOVED -> {
            drawRect(color.copy(alpha = (if (strong) STRONG_VEIL else VEIL) * alpha), page.topLeft, page.size)
            drawLine(color.copy(alpha = alpha), page.bottomLeft, page.topRight, line * 2f)
            drawLine(color.copy(alpha = alpha), page.topLeft, page.bottomRight, line * 2f)
            drawRect(color.copy(alpha = alpha), page.topLeft, page.size, style = Stroke(line * 4f))
            drawLabel(drawn.label, page, color.copy(alpha = alpha), measurer)
        }
        PlannedMark.Kind.PAGE_CHANGED -> {
            drawRect(color.copy(alpha = (if (strong) FILL else FILL / 2f) * alpha), page.topLeft, page.size)
            drawRect(color.copy(alpha = alpha), page.topLeft, page.size, style = Stroke(line * 4f))
            drawLabel(drawn.label, page, color.copy(alpha = alpha), measurer)
        }
    }
}

/** An arrow from [from] to [to], when they are far enough apart to see one. */
private fun DrawScope.drawArrow(from: Offset, to: Offset, color: Color, line: Float) {
    val length = hypot(to.x - from.x, to.y - from.y)
    val head = ARROW_HEAD.toPx()
    if (length < head * 1.5f) return
    drawLine(color, from, to, line, cap = StrokeCap.Round)
    val angle = atan2(to.y - from.y, to.x - from.x)
    val path = Path().apply {
        moveTo(to.x, to.y)
        lineTo(to.x - head * cos(angle - ARROW_SPREAD), to.y - head * sin(angle - ARROW_SPREAD))
        lineTo(to.x - head * cos(angle + ARROW_SPREAD), to.y - head * sin(angle + ARROW_SPREAD))
        close()
    }
    drawPath(path, color)
}

/** The words of what happens to a page, on a chip at its top-left corner. */
private fun DrawScope.drawLabel(label: String?, page: Rect, color: Color, measurer: TextMeasurer) {
    if (label.isNullOrBlank()) return
    val layout = measurer.measure(label, TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
    val padding = LABEL_PADDING.toPx()
    val origin = Offset(page.left + padding, page.top + padding)
    drawRoundRect(color, origin, Size(layout.size.width + padding * 2f, layout.size.height + padding), CornerRadius(CORNER.toPx()))
    drawText(layout, topLeft = Offset(origin.x + padding, origin.y + padding / 2f))
}

private val LINE = 1.5.dp
private val CHOSEN_LINE = 3.dp
private val FRAME_GAP = 2.dp
private val CORNER = 4.dp
private val DASH = 5.dp
private val ARROW_HEAD = 9.dp
private val LABEL_PADDING = 8.dp
private const val ARROW_SPREAD = 0.45f
private const val FILL = 0.10f
private const val STRONG_FILL = 0.22f
private const val VEIL = 0.18f
private const val STRONG_VEIL = 0.32f
private const val FAINT_ALPHA = 0.3f
