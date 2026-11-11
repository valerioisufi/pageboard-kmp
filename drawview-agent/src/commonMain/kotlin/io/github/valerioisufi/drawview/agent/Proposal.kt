package io.github.valerioisufi.drawview.agent

import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.geometry.Rect
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a model proposes to do to a document, by meaning: not a `DocumentChange`, whose format belongs
 * to the log and to the synchronization. Nothing happens until the user accepts it.
 */
@Serializable
public sealed interface ProposalItem {
    /** Replaces the Markdown of the text box [text], as it was when read ([base]). */
    @Serializable
    @SerialName("replace_text")
    public data class ReplaceText(val text: String, val base: String, val markdown: String) : ProposalItem

    /** Removes the text box [text], as it was when read ([base]): it stays hidden in the document. */
    @Serializable
    @SerialName("remove_text")
    public data class RemoveText(val text: String, val base: String) : ProposalItem

    /**
     * Adds [markdown] in the free space of the page [page] (`null`: the last one), going on onto new
     * pages after it when it does not fit; [newPage]: straight on a new page after it. The place is
     * computed by the application, on the page as it is when accepted (see `docs/agent-placement.md`).
     *
     * @param near the text box it is about, on whose page it goes: it is placed next to that box.
     * @param nearArea the area of [page] it is about, for what has no box (handwriting, a figure).
     * @param place how to use what it is about; `null`: below it. [InsertPlace.INSIDE]: in [area].
     * @param fontSize its size in points; `null`: the one of [styleFrom], or the one the page tells.
     * @param color `#RRGGBB` or `#RRGGBBAA`; `null`: the one of [styleFrom], or the blue of the models.
     * @param width the width of its boxes in millimeters, never made narrower; `null`: what the place allows.
     * @param styleFrom a text box whose size and color it takes.
     * @param area the area of [page] it goes in, with [InsertPlace.INSIDE]: at its top-left corner, as
     *   wide as it at most.
     */
    @Serializable
    @SerialName("insert_text")
    public data class InsertText(
        val markdown: String,
        val page: String? = null,
        @SerialName("new_page") val newPage: Boolean = false,
        val near: String? = null,
        @SerialName("near_area") val nearArea: AgentArea? = null,
        @SerialName("where") val place: InsertPlace? = null,
        @SerialName("font_size") val fontSize: Float? = null,
        val color: String? = null,
        val width: Float? = null,
        @SerialName("style_from") val styleFrom: String? = null,
        val area: AgentArea? = null
    ) : ProposalItem

    /** Adds a page after the page [after] (`null`: the last one), of its format. */
    @Serializable
    @SerialName("add_page")
    public data class AddPage(val after: String? = null) : ProposalItem

    /** Removes [elements] of the page [page], of any kind: they stay hidden in the document. */
    @Serializable
    @SerialName("remove_elements")
    public data class RemoveElements(val page: String, val elements: List<ElementRef>) : ProposalItem

    /**
     * Moves [elements] of the page [page] by [dx] and [dy] millimeters, and scales them by [scale]
     * around the top-left corner of the box holding them all.
     */
    @Serializable
    @SerialName("place_elements")
    public data class PlaceElements(
        val page: String,
        val elements: List<ElementRef>,
        val dx: Float = 0f,
        val dy: Float = 0f,
        val scale: Float = 1f
    ) : ProposalItem

    /** Puts [elements] of the page [page] on top of the others, or under them. */
    @Serializable
    @SerialName("reorder_elements")
    public data class ReorderElements(val page: String, val elements: List<ElementRef>, val to: StackPlace) : ProposalItem

    /**
     * Changes the style of the text box [text], as it was when read ([base]).
     *
     * @param color `#RRGGBB` or `#RRGGBBAA`.
     * @param alignment `start`, `center` or `end`.
     */
    @Serializable
    @SerialName("restyle_text")
    public data class RestyleText(
        val text: String,
        val base: String,
        val color: String? = null,
        @SerialName("font_size") val fontSize: Float? = null,
        val alignment: String? = null
    ) : ProposalItem

    /**
     * Changes the text box [text], as it was when read ([base]), in any way at once: its words
     * ([markdown]), its style, its corner ([x], [y], in millimeters from the top-left corner of its
     * page) and the width its lines wrap on. What is `null` stays as it is.
     *
     * @param color `#RRGGBB` or `#RRGGBBAA`.
     * @param alignment `start`, `center` or `end`.
     */
    @Serializable
    @SerialName("update_text")
    public data class UpdateText(
        val text: String,
        val base: String,
        val markdown: String? = null,
        @SerialName("font_size") val fontSize: Float? = null,
        val color: String? = null,
        val alignment: String? = null,
        val width: Float? = null,
        val x: Float? = null,
        val y: Float? = null
    ) : ProposalItem

    /**
     * Draws a stroke on the page [page] through [points], `[x, y]` in millimeters from its top-left corner.
     *
     * @param color `#RRGGBB` or `#RRGGBBAA`; the blue of the models by default.
     * @param width the width of its line in millimeters.
     * @param tool `pressure_pen`, `marker`, `highlighter` or `dashed_line`.
     */
    @Serializable
    @SerialName("add_stroke")
    public data class AddStroke(
        val page: String,
        val points: List<List<Float>>,
        val color: String? = null,
        val width: Float? = null,
        val tool: String? = null
    ) : ProposalItem

    /**
     * Changes the color or the width of the stroke [stroke], as it was when read ([base]). The stroke
     * keeps its place and its order, under a new id.
     */
    @Serializable
    @SerialName("restyle_stroke")
    public data class RestyleStroke(
        val stroke: String,
        val base: String,
        val color: String? = null,
        val width: Float? = null
    ) : ProposalItem

    /**
     * Adds the image file [path] to the page [page] (`null`: the last one), in [area] (its proportions
     * kept), or in the free space of the page.
     *
     * Once proposed, [path] is the copy the application keeps among the files of the documents, and
     * [hash] its content: what a client sends for them is replaced.
     */
    @Serializable
    @SerialName("add_image")
    public data class AddImage(
        val path: String,
        val page: String? = null,
        val area: AgentArea? = null,
        val hash: String = ""
    ) : ProposalItem

    /** Removes the page [page], as it was when read ([base]): it goes to the trash of the pages. */
    @Serializable
    @SerialName("remove_page")
    public data class RemovePage(val page: String, val base: String) : ProposalItem

    /** Moves the page [page] after the page [after] (`null`: before every page). */
    @Serializable
    @SerialName("move_page")
    public data class MovePage(val page: String, val after: String? = null) : ProposalItem

    /** Adds a copy of the page [page], with all it holds, after the page [after] (`null`: after itself). */
    @Serializable
    @SerialName("duplicate_page")
    public data class DuplicatePage(val page: String, val after: String? = null) : ProposalItem

    /**
     * Changes the format of the page [page], as it was when read ([base]): its size in millimeters and
     * its background, what is not given staying as it is.
     *
     * @param background `plain`, `ruled`, `grid`, `dotted`, or `document` for the background of the document.
     * @param spacingMm the distance between the lines or the dots of the background.
     * @param backgroundColor `#RRGGBB` or `#RRGGBBAA`.
     */
    @Serializable
    @SerialName("set_page_format")
    public data class SetPageFormat(
        val page: String,
        val base: String,
        @SerialName("width_mm") val widthMm: Float? = null,
        @SerialName("height_mm") val heightMm: Float? = null,
        val background: String? = null,
        @SerialName("spacing_mm") val spacingMm: Float? = null,
        @SerialName("background_color") val backgroundColor: String? = null
    ) : ProposalItem

    /** Renames the document [title]. */
    @Serializable
    @SerialName("rename_document")
    public data class RenameDocument(val title: String) : ProposalItem
}

/** An element of a page, as it was when read ([base]). */
@Serializable
public data class ElementRef(val id: String, val base: String)

/** Where elements go in the stack of their page. */
@Serializable
public enum class StackPlace {
    @SerialName("front")
    FRONT,

    @SerialName("back")
    BACK
}

/** An area of a page in millimeters, from its top-left corner. */
@Serializable
public data class AgentArea(val x: Float, val y: Float, val width: Float, val height: Float) {
    /** The area as a rectangle. */
    public val rect: Rect get() = Rect(x, y, x + width, y + height)

    /** Whether it is a real area, inside a page of this size. */
    public fun isInside(pageWidthMm: Float, pageHeightMm: Float): Boolean =
        listOf(x, y, width, height).all { it.isFinite() } && width > 0f && height > 0f &&
            x >= 0f && y >= 0f && x + width <= pageWidthMm && y + height <= pageHeightMm
}

/** How a new text goes next to what it is about. */
@Serializable
public enum class InsertPlace {
    /** Below it, or to its right when there is no room below. */
    @SerialName("below")
    BELOW,

    /** Beside it, as a short note in the margin of a book. */
    @SerialName("margin")
    MARGIN,

    /** In the area given, whatever is there: the answer warns when it does not fit or covers something. */
    @SerialName("inside")
    INSIDE
}

/** What became of an item of a proposal. */
public enum class ItemOutcome(public val wireName: String) {
    WAITING("waiting"),
    ACCEPTED("accepted"),
    REJECTED("rejected"),

    /** Accepted, but what it was made on changed after the model read it: nothing was overwritten. */
    STALE("stale")
}

/**
 * An item of a [Proposal].
 *
 * @param before the text it replaces or removes as it was when proposed, to show beside the new one.
 * @param placed where its text went, once accepted.
 * @param page the index (from 0) of the page it is on when proposed, for an item on a page or its elements.
 * @param changed the elements it made or changed, once accepted, with the base to change them again by.
 */
public data class ProposalEntry(
    val item: ProposalItem,
    val before: String? = null,
    val outcome: ItemOutcome = ItemOutcome.WAITING,
    val placed: List<PlannedPiece> = emptyList(),
    val page: Int? = null,
    val changed: List<ElementRef> = emptyList()
)

/**
 * What a client proposed for the document [documentId], waiting for the user or decided.
 *
 * @param client the name the client gave itself: for the user to read, not proof of anything.
 */
public data class Proposal(
    val id: String,
    val documentId: String,
    val client: String,
    val title: String,
    val entries: List<ProposalEntry>
) {
    /** Whether the user has still to decide. */
    public val isWaiting: Boolean get() = entries.any { it.outcome == ItemOutcome.WAITING }
}

/**
 * What a model read of this text, to tell later whether it changed: the versions of its content and a
 * hash of its text (a text written before the versions has none). Opaque to the client.
 */
internal fun DrawText.base(): String = "${contentVersions}#${fnv1a(text).toString(16)}"

/** The FNV-1a hash of [text]: to notice a change, not against someone making one on purpose. */
internal fun fnv1a(text: String): ULong {
    var hash = 0xcbf29ce484222325UL
    for (char in text) {
        hash = hash xor char.code.toULong()
        hash *= 0x100000001b3UL
    }
    return hash
}

/** The text box [id] shown in this document and its page, or `null`. */
internal fun DrawDocument.findText(id: String): Pair<DrawPage, DrawText>? {
    for (page in pages) {
        val text = page.texts.firstOrNull { it.id == id && it.removed == null }
        if (text != null) return page to text
    }
    return null
}
