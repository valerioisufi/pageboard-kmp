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
     * @param place how to use what it is about; `null`: below it.
     */
    @Serializable
    @SerialName("insert_text")
    public data class InsertText(
        val markdown: String,
        val page: String? = null,
        @SerialName("new_page") val newPage: Boolean = false,
        val near: String? = null,
        @SerialName("near_area") val nearArea: AgentArea? = null,
        @SerialName("where") val place: InsertPlace? = null
    ) : ProposalItem

    /** Adds a page after the page [after] (`null`: the last one), of its format. */
    @Serializable
    @SerialName("add_page")
    public data class AddPage(val after: String? = null) : ProposalItem
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
    MARGIN
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
 */
public data class ProposalEntry(
    val item: ProposalItem,
    val before: String? = null,
    val outcome: ItemOutcome = ItemOutcome.WAITING
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
private fun fnv1a(text: String): ULong {
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
