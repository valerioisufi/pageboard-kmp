package io.github.valerioisufi.drawview.agent

import io.github.valerioisufi.drawview.model.DrawCustomElement
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.DrawStroke
import io.github.valerioisufi.drawview.model.DrawTextMarkup
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.store.DocumentSummary
import io.github.valerioisufi.drawview.model.geometry.Rect
import io.github.valerioisufi.drawview.model.shape.ShapeBox
import io.github.valerioisufi.drawview.model.shape.ShapeElement
import io.github.valerioisufi.drawview.model.shape.ShapeGeometry
import io.github.valerioisufi.drawview.model.shape.ShapeHead
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.round

// The entries the tools answer with, and how they are made from the documents

internal fun LibraryProposal.toStatus() = ProposalStatus(
    id,
    null,
    if (isWaiting) "waiting" else "decided",
    outcomes.map { it.wireName },
    created = created.takeIf { !isWaiting }
)

internal fun Proposal.toStatus() = ProposalStatus(
    id,
    documentId,
    if (isWaiting) "waiting" else "decided",
    entries.map { it.outcome.wireName },
    placed = if (isWaiting) null else entries.map { entry -> entry.placed.map { it.toEntry(null, known = true) } },
    changed = if (isWaiting) null else entries.map { it.changed }
)

internal fun ProposalPlan.toAnswer(items: List<ProposalItem>) = PlanAnswer(
    isMeasured,
    this.items.mapIndexed { index, planned ->
        PlanItemEntry(
            kind = items[index].wireKind,
            text = when (val item = items[index]) {
                is ProposalItem.ReplaceText -> item.text
                is ProposalItem.RestyleText -> item.text
                is ProposalItem.UpdateText -> item.text
                else -> null
            },
            stale = planned.isStale,
            fontSize = planned.pieces.firstOrNull()?.fontSize,
            pieces = planned.pieces.map { it.toEntry(pageOrder.indexOf(it.pageId), known = items[index] !is ProposalItem.InsertText) },
            pagesAdded = planned.pagesAdded,
            warnings = planned.warnings.map { it.toEntry() }
        )
    }
)

internal val ProposalItem.wireKind: String
    get() = when (this) {
        is ProposalItem.ReplaceText -> "replace_text"
        is ProposalItem.RemoveText -> "remove_text"
        is ProposalItem.InsertText -> "insert_text"
        is ProposalItem.AddPage -> "add_page"
        is ProposalItem.RemoveElements -> "remove_elements"
        is ProposalItem.PlaceElements -> "place_elements"
        is ProposalItem.ReorderElements -> "reorder_elements"
        is ProposalItem.RestyleText -> "restyle_text"
        is ProposalItem.UpdateText -> "update_text"
        is ProposalItem.AddStroke -> "add_stroke"
        is ProposalItem.RestyleStroke -> "restyle_stroke"
        is ProposalItem.AddImage -> "add_image"
        is ProposalItem.RemovePage -> "remove_page"
        is ProposalItem.MovePage -> "move_page"
        is ProposalItem.DuplicatePage -> "duplicate_page"
        is ProposalItem.SetPageFormat -> "set_page_format"
        is ProposalItem.RenameDocument -> "rename_document"
        is ProposalItem.SetDocumentDefaults -> "set_document_defaults"
        is ProposalItem.RestorePages -> "restore_pages"
    }

/**
 * A piece as a client reads it; a page the plan would add has no id for it until it is there, and
 * neither has a text it would add ([known]: the piece is of an element of the document).
 */
internal fun PlannedPiece.toEntry(pageIndex: Int?, known: Boolean) = PieceEntry(
    page = pageId.takeIf { !isOnNewPage || pageIndex == null },
    pageIndex = pageIndex,
    newPage = isOnNewPage,
    id = textId.takeIf { known },
    x = box.left.tenths(),
    y = box.top.tenths(),
    width = box.width.tenths(),
    height = box.height.tenths()
)

internal fun PlacementWarning.toEntry(): WarningEntry = when (this) {
    is PlacementWarning.FarFromAnchor -> WarningEntry("far_from_anchor", byMm = byMm.tenths())
    PlacementWarning.MovedToNewPage -> WarningEntry("moved_to_new_page")
    is PlacementWarning.Split -> WarningEntry("split", pieces = pieces)
    is PlacementWarning.Narrowed -> WarningEntry("narrowed", fromMm = fromMm.tenths())
    is PlacementWarning.Overlaps -> WarningEntry("overlaps", with = with)
    is PlacementWarning.PastArea -> WarningEntry("past_area", byMm = byMm.tenths())
    PlacementWarning.OutsidePage -> WarningEntry("outside_page")
    is PlacementWarning.PastPageEnd -> WarningEntry("past_page_end", byMm = byMm.tenths())
    is PlacementWarning.ChainOverflow -> WarningEntry("chain_overflow", byMm = byMm.tenths())
}

internal fun Rect.toArea() = AgentArea(left.tenths(), top.tenths(), width.tenths(), height.tenths())

/** To the tenth of a millimeter: what a place can be told to. */
internal fun Float.tenths(): Float = round(this * 10f) / 10f

internal fun Float.hundredths(): Float = round(this * 100f) / 100f

/** To half a point: what the size of a text can be told to. */
internal fun Float.halves(): Float = round(this * 2f) / 2f

/** An ARGB color as `#RRGGBB`, or `#RRGGBBAA` when it is not opaque. */
internal fun colorHex(argb: Int): String {
    val rgb = (argb and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()
    val alpha = (argb ushr 24) and 0xFF
    return if (alpha == 0xFF) "#$rgb" else "#$rgb${alpha.toString(16).padStart(2, '0').uppercase()}"
}

internal fun DrawStroke.toEntry(): StrokeEntry {
    val box = bounds
    return StrokeEntry(
        id = id,
        x = box.left.tenths(),
        y = box.top.tenths(),
        width = box.width.tenths(),
        height = box.height.tenths(),
        color = colorHex(brushConfig.color),
        strokeWidth = brushConfig.size.hundredths(),
        tool = brushConfig.tool.name.lowercase(),
        base = elementBase()
    )
}


@Serializable
internal data class DocumentEntry(
    val id: String,
    val title: String,
    val pages: Int,
    val folder: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("modified_at") val modifiedAt: Long? = null,
    @SerialName("last_opened_at") val lastOpenedAt: Long? = null,
    @SerialName("is_shared") val isShared: Boolean = false,
    @SerialName("has_pdf") val hasPdf: Boolean = false
)

/** [summary] as a model reads it in a list of documents. */
internal fun DocumentSummary.toEntry() = DocumentEntry(
    id, name, pageCount, folderId,
    createdAt = createdAt.takeIf { it > 0L },
    modifiedAt = modifiedAt.takeIf { it > 0L },
    lastOpenedAt = lastOpenedAt.takeIf { it > 0L },
    isShared = isShared,
    hasPdf = hasPdf
)

/** The format a new page of a document takes: its size and background. */
@Serializable
internal data class FormatEntry(
    @SerialName("width_mm") val widthMm: Float,
    @SerialName("height_mm") val heightMm: Float,
    val background: String,
    @SerialName("spacing_mm") val spacingMm: Float? = null,
    @SerialName("background_color") val backgroundColor: String
)

/** [background] on a page of [widthMm] by [heightMm], as a model reads it. */
internal fun formatEntryOf(widthMm: Float, heightMm: Float, background: PageBackground) =
    FormatEntry(widthMm, heightMm, PageItems.kindOf(background), spacingOf(background), colorHex(background.backgroundColor))

/** The distance between the lines or the dots of [background], or `null` for a plain one. */
internal fun spacingOf(background: PageBackground): Float? = when (background) {
    is PageBackground.Solid -> null
    is PageBackground.Ruled -> background.spacingMm
    is PageBackground.Grid -> background.spacingMm
    is PageBackground.Dotted -> background.spacingMm
}

/** A page in the trash of the pages of its document, as a model reads it. */
@Serializable
internal data class RemovedPageEntry(
    val id: String,
    @SerialName("former_index") val formerIndex: Int,
    @SerialName("removed_at") val removedAt: Long,
    @SerialName("width_mm") val widthMm: Float,
    @SerialName("height_mm") val heightMm: Float,
    val texts: Int,
    val strokes: Int,
    val images: Int,
    val pdfs: Int,
    @SerialName("text_start") val textStart: String? = null
)

/** A highlight, underline or strikeout of the text of a PDF page, as a model reads it. */
@Serializable
internal data class MarkupEntry(
    val id: String,
    val kind: String,
    val color: String,
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val base: String
)

/** [markup] on [page] as a model reads it: its box in the millimeters of the page. */
internal fun markupEntryOf(page: DrawPage, markup: DrawTextMarkup): MarkupEntry {
    val matrix = markup.pointsToPageMm(page)
    val box = markup.rects.map { matrix.mapRect(it) }.reduceOrNull { all, next -> all.union(next) } ?: Rect(0f, 0f, 0f, 0f)
    return MarkupEntry(
        markup.id, markup.kind.name.lowercase(), colorHex(markup.color), markup.text,
        box.left.tenths(), box.top.tenths(), box.width.tenths(), box.height.tenths(), markup.elementBase()
    )
}

@Serializable
internal data class DocumentContent(
    val id: String,
    val title: String,
    @SerialName("read_only") val readOnly: Boolean,
    val pages: List<PageEntry>,
    @SerialName("new_page_format") val defaults: FormatEntry? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("modified_at") val modifiedAt: Long? = null,
    @SerialName("last_opened_at") val lastOpenedAt: Long? = null,
    @SerialName("is_shared") val isShared: Boolean = false,
    @SerialName("has_pdf") val hasPdf: Boolean = false,
    @SerialName("removed_pages") val removedPages: Int = 0,
    val measured: Boolean? = null,
    val notice: String? = null
)

@Serializable
internal data class PageEntry(
    val id: String,
    val index: Int,
    @SerialName("width_mm") val widthMm: Float,
    @SerialName("height_mm") val heightMm: Float,
    val texts: Int,
    val strokes: Int,
    val images: Int,
    val pdfs: Int,
    val base: String,
    @SerialName("pdf_page") val pdfPage: Int? = null,
    @SerialName("text_list") val textList: List<TextStart>? = null,
    @SerialName("texts_left_out") val textsLeftOut: Int? = null,
    @SerialName("text_sizes") val textSizes: TextSizesEntry? = null
)

/** A text box in the list of a document: where it is, its size and how it begins. */
@Serializable
internal data class TextStart(
    val id: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    @SerialName("font_size") val fontSize: Float,
    val start: String,
    val base: String
)

@Serializable
internal data class PageContent(
    val id: String,
    val index: Int,
    @SerialName("width_mm") val widthMm: Float,
    @SerialName("height_mm") val heightMm: Float,
    val measured: Boolean,
    val base: String,
    val background: String,
    @SerialName("spacing_mm") val spacingMm: Float? = null,
    @SerialName("background_color") val backgroundColor: String? = null,
    val strokes: Int,
    val texts: List<TextEntry>,
    val images: List<ImageEntry>,
    val pdfs: List<PdfEntry> = emptyList(),
    @SerialName("custom_elements") val customElements: List<CustomEntry> = emptyList(),
    val markups: List<MarkupEntry> = emptyList(),
    @SerialName("stroke_list") val strokeList: List<StrokeEntry>? = null,
    @SerialName("strokes_left_out") val strokesLeftOut: Int? = null,
    @SerialName("ink_area") val inkArea: AgentArea? = null,
    @SerialName("pdf_pages") val pdfPages: Int,
    @SerialName("free_areas") val freeAreas: List<AgentArea>,
    @SerialName("text_sizes") val textSizes: TextSizesEntry? = null,
    @SerialName("pdf_text") val pdfText: String? = null,
    val handwriting: String? = null,
    val notice: String? = null
)

/** The sizes of the characters on a page, in points, each one when the page tells it. */
@Serializable
internal data class TextSizesEntry(
    val typed: Float? = null,
    val slide: Float? = null,
    val handwriting: Float? = null,
    val note: Float
)

@Serializable
internal data class TextEntry(
    val id: String,
    val markdown: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    @SerialName("font_size") val fontSize: Float,
    @SerialName("goes_on_into") val next: String? = null,
    val base: String,
    val color: String,
    val alignment: String,
    val rotation: Float? = null
)

@Serializable
internal data class ImageEntry(
    val id: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val base: String,
    val rotation: Float? = null
)

@Serializable
internal data class PdfEntry(
    val id: String,
    @SerialName("pdf_page") val pdfPage: Int,
    val base: String
)

@Serializable
internal data class CustomEntry(
    val id: String,
    val type: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val base: String,
    /** What a shape of the shapes tool is (its type `drawview.shape`). */
    val shape: ShapeEntry? = null
)

/**
 * A shape of the shapes tool as a model reads it: its kind (`line`, `arrow`, `double_arrow`,
 * `rectangle`, ...), its outline, its fill; the ends of a line ([from], [to], page millimeters).
 */
@Serializable
internal data class ShapeEntry(
    val kind: String,
    val color: String,
    val width: Float,
    val outline: String,
    val fill: String? = null,
    val from: List<Float>? = null,
    val to: List<Float>? = null,
    val rotation: Float? = null
)

/** [value], the value of [element], as [ShapeEntry]. */
internal fun shapeEntryOf(element: DrawCustomElement, value: ShapeElement): ShapeEntry {
    val box = ShapeBox(element.x, element.y, element.width, element.height, element.rotation)
    val heads = listOf(value.startHead, value.endHead).count { it != ShapeHead.NONE }
    val kind = when {
        !value.kind.isLine -> value.kind.name.lowercase()
        heads == 2 -> "double_arrow"
        // An arrow is read from its tail to its head
        heads == 1 -> "arrow"
        else -> "line"
    }
    var ends = if (value.kind.isLine) ShapeGeometry.lineEnds(box) else null
    if (ends != null && value.startHead != ShapeHead.NONE && value.endHead == ShapeHead.NONE) ends = ends.second to ends.first
    return ShapeEntry(
        kind = kind,
        color = colorHex(value.stroke),
        width = value.widthMm.tenths().coerceAtLeast(0.1f),
        outline = value.dash.name.lowercase(),
        fill = value.fill?.let(::colorHex),
        from = ends?.first?.let { listOf(it.x.tenths(), it.y.tenths()) },
        to = ends?.second?.let { listOf(it.x.tenths(), it.y.tenths()) },
        rotation = element.rotation.takeIf { !value.kind.isLine && it != 0f }?.tenths()
    )
}

@Serializable
internal data class StrokeEntry(
    val id: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val color: String,
    @SerialName("stroke_width") val strokeWidth: Float,
    val tool: String,
    val base: String
)

@Serializable
internal data class StrokeContent(
    val id: String,
    val page: String,
    @SerialName("stroke") val entry: StrokeEntry,
    val points: List<List<Float>>,
    @SerialName("points_in_all") val pointsInAll: Int,
    val notice: String
)

@Serializable
internal data class SearchEntry(
    val document: String,
    val title: String,
    val page: String? = null,
    val snippet: String
)

@Serializable
internal data class ProposalStatus(
    val proposal: String,
    val document: String?,
    val state: String,
    val items: List<String>,
    val plan: PlanAnswer? = null,
    val placed: List<List<PieceEntry>>? = null,
    val changed: List<List<ElementRef>>? = null,
    val created: List<String>? = null,
    val mode: String? = null
)

@Serializable
internal data class ActivityEntry(
    val id: String,
    val document: String? = null,
    val client: String,
    val title: String,
    val items: Int,
    val at: Long,
    val undone: Boolean,
    val undoable: Boolean? = null
)

@Serializable
internal data class FolderEntry(val id: String, val name: String, val parent: String? = null)

@Serializable
internal data class TrashEntry(
    val id: String,
    val kind: String,
    val name: String,
    val folder: String? = null,
    @SerialName("trashed_at") val trashedAt: Long
)

@Serializable
internal data class PlanAnswer(
    val measured: Boolean,
    val items: List<PlanItemEntry>
)

@Serializable
internal data class PlanItemEntry(
    val kind: String,
    val text: String? = null,
    val stale: Boolean,
    @SerialName("font_size") val fontSize: Float? = null,
    val pieces: List<PieceEntry>,
    @SerialName("pages_added") val pagesAdded: Int,
    val warnings: List<WarningEntry>
)

@Serializable
internal data class PieceEntry(
    val page: String? = null,
    @SerialName("page_index") val pageIndex: Int? = null,
    @SerialName("new_page") val newPage: Boolean,
    val id: String? = null,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)

@Serializable
internal data class WarningEntry(
    val code: String,
    @SerialName("by_mm") val byMm: Float? = null,
    @SerialName("from_mm") val fromMm: Float? = null,
    val pieces: Int? = null,
    val with: List<String>? = null
)
