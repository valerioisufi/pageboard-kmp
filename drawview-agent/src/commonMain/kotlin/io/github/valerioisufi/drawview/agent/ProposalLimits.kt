package io.github.valerioisufi.drawview.agent

import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawElement
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.DrawPdf
import io.github.valerioisufi.drawview.model.DrawTextMarkup
import io.github.valerioisufi.drawview.model.placement.FreeSpace
import io.github.valerioisufi.drawview.model.placement.TextSizePolicy
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject

/**
 * The bounds of a proposal.
 *
 * @param maxItems how many items.
 * @param maxTextLength how many characters of text in an item.
 * @param maxTotalLength how many characters of text in all.
 * @param maxPlannedLength how many characters of text may be planned for a client: more is proposed
 *   without being previewed, and planned only when the user accepts it.
 */
internal class ProposalLimits(
    private val maxItems: Int = 200,
    private val maxTextLength: Int = 64 * 1024,
    private val maxTotalLength: Int = 1024 * 1024,
    private val maxTitleLength: Int = 200,
    private val maxPlannedLength: Int = 32 * 1024,
    private val maxElements: Int = 500,
    private val maxStrokePoints: Int = 2000,
    private val maxLibraryItems: Int = 100
) {
    private companion object {
        const val MAX_PATH_LENGTH = 4096

        /** The narrowest a model may ask a text box to be. */
        const val MIN_TEXT_WIDTH_MM = 20f
    }

    /** Whether [items] are few enough to be planned for a client, on the thread of the document. */
    fun canPlan(items: List<ProposalItem>): Boolean = items.sumOf { item ->
        when (item) {
            is ProposalItem.ReplaceText -> item.markdown.length
            is ProposalItem.InsertText -> item.markdown.length
            is ProposalItem.UpdateText -> item.markdown?.length ?: 0
            else -> 0
        }
    } <= maxPlannedLength

    fun check(title: String, items: List<ProposalItem>) {
        if (title.isBlank() || title.length > maxTitleLength) refuse("A title of at most $maxTitleLength characters is needed.")
        checkItems(items)
    }

    private fun refuse(message: String): Nothing = throw AgentToolException(AgentErrorCode.REFUSED, message)

    /** The bounds of a proposal for the library. */
    fun checkLibrary(title: String, items: List<LibraryItem>) {
        if (title.isBlank() || title.length > maxTitleLength) refuse("A title of at most $maxTitleLength characters is needed.")
        if (items.isEmpty()) refuse("A proposal needs at least one item.")
        if (items.size > maxLibraryItems) refuse("A proposal for the library has at most $maxLibraryItems items.")
        fun name(name: String) {
            if (name.isBlank() || name.length > PageItems.MAX_TITLE_LENGTH) refuse("A name of at most ${PageItems.MAX_TITLE_LENGTH} characters is needed.")
        }
        for (item in items) {
            when (item) {
                is LibraryItem.CreateDocument -> {
                    name(item.title)
                    if (listOf(item.widthMm, item.heightMm).any { !it.isFinite() || it < PageItems.MIN_PAGE_SIDE_MM || it > PageItems.MAX_PAGE_SIDE_MM }) {
                        refuse("A side of a page is between ${PageItems.MIN_PAGE_SIDE_MM} and ${PageItems.MAX_PAGE_SIDE_MM} millimeters.")
                    }
                }
                is LibraryItem.CreateFolder -> name(item.name)
                is LibraryItem.RenameFolder -> name(item.name)
                else -> {}
            }
        }
    }

    /** The bounds of the items of a proposal, and what they refer to making sense among them. */
    fun checkItems(items: List<ProposalItem>) {
        if (items.isEmpty()) refuse("A proposal needs at least one item.")
        if (items.size > maxItems) refuse("A proposal has at most $maxItems items.")
        var total = 0
        val targets = mutableSetOf<String>()
        for (item in items) {
            val text = when (item) {
                is ProposalItem.ReplaceText -> item.markdown
                is ProposalItem.InsertText -> item.markdown
                is ProposalItem.UpdateText -> item.markdown.orEmpty()
                else -> ""
            }
            if (text.length > maxTextLength) refuse("A text of an item has at most $maxTextLength characters: use several items.")
            total += text.length
            if (total > maxTotalLength) refuse("A proposal has at most $maxTotalLength characters of text.")
            if ((item is ProposalItem.ReplaceText || item is ProposalItem.InsertText) && text.isBlank()) {
                refuse("A text cannot be blank: to remove a text box use remove_text.")
            }
            for (target in ElementItems.targetsOf(item)) {
                if (!targets.add(target)) refuse("An element can be changed by one item of a proposal only.")
            }
            PageItems.targetOf(item)?.let { page ->
                if (!targets.add("page:$page")) refuse("A page can be removed, moved or formatted by one item of a proposal only.")
            }
            checkElementItem(item, items)
        }
        val removed = items.filterIsInstance<ProposalItem.RemoveText>().mapTo(mutableSetOf()) { it.text }
        for (item in items.filterIsInstance<ProposalItem.InsertText>()) checkAnchor(item, removed)
    }

    /** The values of an item on elements, whatever the document. */
    private fun checkElementItem(item: ProposalItem, items: List<ProposalItem>) {
        fun color(text: String?) {
            if (text != null && ElementItems.parseColor(text) == null) refuse("A color is #RRGGBB or #RRGGBBAA.")
        }
        fun refs(refs: List<ElementRef>) {
            if (refs.isEmpty()) refuse("An item needs at least one element.")
            if (refs.size > maxElements) refuse("An item changes at most $maxElements elements.")
        }
        when (item) {
            is ProposalItem.RemoveElements -> refs(item.elements)
            is ProposalItem.PlaceElements -> {
                refs(item.elements)
                if (!item.dx.isFinite() || !item.dy.isFinite()) refuse("dx and dy are millimeters.")
                if (!item.scale.isFinite() || item.scale !in ElementItems.MIN_SCALE..ElementItems.MAX_SCALE) {
                    refuse("scale is between ${ElementItems.MIN_SCALE} and ${ElementItems.MAX_SCALE}.")
                }
            }
            is ProposalItem.ReorderElements -> refs(item.elements)
            is ProposalItem.RestyleText -> {
                color(item.color)
                if (item.fontSize != null && (!item.fontSize.isFinite() || item.fontSize <= 0f || item.fontSize > ElementItems.MAX_FONT_SIZE)) {
                    refuse("font_size is between 0 and ${ElementItems.MAX_FONT_SIZE}.")
                }
                if (item.alignment != null && ElementItems.parseAlignment(item.alignment) == null) refuse("alignment is start, center or end.")
                if (item.color == null && item.fontSize == null && item.alignment == null) refuse("restyle_text needs color, font_size or alignment.")
            }
            is ProposalItem.UpdateText -> {
                color(item.color)
                if (item.markdown != null && item.markdown.isBlank()) refuse("A text cannot be blank: to remove a text box use remove_text.")
                if (item.fontSize != null && (!item.fontSize.isFinite() || item.fontSize <= 0f || item.fontSize > ElementItems.MAX_FONT_SIZE)) {
                    refuse("font_size is between 0 and ${ElementItems.MAX_FONT_SIZE}.")
                }
                if (item.alignment != null && ElementItems.parseAlignment(item.alignment) == null) refuse("alignment is start, center or end.")
                if (item.width != null && (!item.width.isFinite() || item.width < MIN_TEXT_WIDTH_MM || item.width > PageItems.MAX_PAGE_SIDE_MM)) {
                    refuse("width is at least $MIN_TEXT_WIDTH_MM millimeters, and fits its page.")
                }
                if (listOf(item.x, item.y).any { it != null && !it.isFinite() }) refuse("x and y are millimeters.")
                if (listOf(item.markdown, item.fontSize, item.color, item.alignment, item.width, item.x, item.y).all { it == null }) {
                    refuse("update_text needs something to change.")
                }
            }
            is ProposalItem.AddStroke -> {
                color(item.color)
                width(item.width)
                if (item.tool != null && item.tool !in ElementItems.DRAWING_TOOLS) refuse("tool is pressure_pen, marker, highlighter or dashed_line.")
                if (item.points.size < 2 || item.points.size > maxStrokePoints) refuse("A stroke has from 2 to $maxStrokePoints points.")
                if (item.points.any { it.size != 2 || !it[0].isFinite() || !it[1].isFinite() }) refuse("A point is [x, y] in millimeters.")
            }
            is ProposalItem.RestyleStroke -> {
                color(item.color)
                width(item.width)
                if (item.color == null && item.width == null) refuse("restyle_stroke needs color or width.")
            }
            is ProposalItem.AddImage -> if (item.path.isBlank() || item.path.length > MAX_PATH_LENGTH) refuse("path is the path of an image file.")
            is ProposalItem.SetPageFormat -> {
                fun side(value: Float?) {
                    if (value != null && (!value.isFinite() || value < PageItems.MIN_PAGE_SIDE_MM || value > PageItems.MAX_PAGE_SIDE_MM)) {
                        refuse("A side of a page is between ${PageItems.MIN_PAGE_SIDE_MM} and ${PageItems.MAX_PAGE_SIDE_MM} millimeters.")
                    }
                }
                side(item.widthMm)
                side(item.heightMm)
                color(item.backgroundColor)
                if (item.background != null && item.background !in PageItems.BACKGROUNDS) refuse("background is plain, ruled, grid, dotted or document.")
                if (item.spacingMm != null && (!item.spacingMm.isFinite() || item.spacingMm < 1f || item.spacingMm > 100f)) refuse("spacing_mm is between 1 and 100.")
                if (listOf(item.widthMm, item.heightMm, item.background, item.spacingMm, item.backgroundColor).all { it == null }) {
                    refuse("set_page_format needs a size or a background.")
                }
            }
            is ProposalItem.RenameDocument -> {
                if (item.title.isBlank() || item.title.length > PageItems.MAX_TITLE_LENGTH) refuse("A title of at most ${PageItems.MAX_TITLE_LENGTH} characters is needed.")
                if (items.count { it is ProposalItem.RenameDocument } > 1) refuse("A document is renamed by one item of a proposal only.")
            }
            else -> {}
        }
    }

    private fun width(width: Float?) {
        if (width != null && (!width.isFinite() || width <= 0f || width > ElementItems.MAX_STROKE_WIDTH)) {
            refuse("width is between 0 and ${ElementItems.MAX_STROKE_WIDTH} millimeters.")
        }
    }

    private fun checkAnchor(item: ProposalItem.InsertText, removed: Set<String>) {
        if (item.fontSize != null && (!item.fontSize.isFinite() || item.fontSize < TextSizePolicy.MIN || item.fontSize > ElementItems.MAX_FONT_SIZE)) {
            refuse("font_size is between ${TextSizePolicy.MIN} and ${ElementItems.MAX_FONT_SIZE}.")
        }
        if (item.color != null && ElementItems.parseColor(item.color) == null) refuse("A color is #RRGGBB or #RRGGBBAA.")
        if (item.width != null && (!item.width.isFinite() || item.width < MIN_TEXT_WIDTH_MM || item.width > PageItems.MAX_PAGE_SIDE_MM)) {
            refuse("width is at least $MIN_TEXT_WIDTH_MM millimeters, and fits its page.")
        }
        if (item.styleFrom != null && item.styleFrom in removed) refuse("A text cannot take the style of a text box the same proposal removes.")
        if (item.place == InsertPlace.INSIDE) {
            if (item.area == null || item.page == null) refuse("where inside needs page and area.")
            if (item.near != null || item.nearArea != null || item.newPage) refuse("where inside goes without near, near_area and new_page.")
            return
        }
        if (item.area != null) refuse("area goes with where inside: near_area is the area a text is about.")
        if (item.near != null) {
            if (item.page != null || item.nearArea != null) refuse("near goes without page and near_area: the page is the one of the text box.")
            if (item.near in removed) refuse("A text cannot go next to a text box the same proposal removes.")
        }
        if (item.nearArea != null) {
            if (item.page == null) refuse("near_area needs the page it is on.")
            if (item.newPage) refuse("near_area goes without new_page.")
        }
        if (item.place != null && item.near == null && item.nearArea == null) refuse("where needs near or near_area.")
    }
}

/** The text [item] replaces or removes; fails when what it refers to is not in this document. */
internal fun DrawDocument.beforeOf(item: ProposalItem): String? {
    fun missing(what: String): Nothing = throw AgentToolException(AgentErrorCode.NOT_FOUND, "There is no $what in this document: read it again.")
    return when (item) {
        is ProposalItem.ReplaceText -> (findText(item.text) ?: missing("text \"${item.text.take(64)}\"")).second.text
        is ProposalItem.RemoveText -> (findText(item.text) ?: missing("text \"${item.text.take(64)}\"")).second.text
        is ProposalItem.InsertText -> {
            val page = item.page?.let { id -> pages.firstOrNull { it.id == id } ?: missing("page \"${id.take(64)}\"") }
            if (item.near != null && findText(item.near) == null) missing("text \"${item.near.take(64)}\"")
            if (item.styleFrom != null && findText(item.styleFrom) == null) missing("text \"${item.styleFrom.take(64)}\"")
            val on = page ?: item.near?.let { findText(it)?.first } ?: pages.lastOrNull()
            if (item.area != null && on != null && !item.area.isInside(on.widthMm, on.heightMm)) {
                throw AgentToolException(AgentErrorCode.REFUSED, "area is outside the page, which is ${on.widthMm} by ${on.heightMm} millimeters.")
            }
            if (item.width != null && on != null) {
                val room = item.area?.width ?: (on.widthMm - 2 * FreeSpace.DEFAULT_PAGE_MARGIN_MM)
                if (item.width > room) throw AgentToolException(AgentErrorCode.REFUSED, "width is at most $room millimeters there.")
            }
            if (item.nearArea != null && page != null && !item.nearArea.isInside(page.widthMm, page.heightMm)) {
                throw AgentToolException(AgentErrorCode.REFUSED, "near_area is outside the page, which is ${page.widthMm} by ${page.heightMm} millimeters.")
            }
            null
        }
        is ProposalItem.AddPage -> {
            if (item.after != null && pages.none { it.id == item.after }) missing("page \"${item.after.take(64)}\"")
            null
        }
        is ProposalItem.RemoveElements -> {
            elementsOf(item.page, item.elements, ::missing)
            null
        }
        is ProposalItem.PlaceElements -> {
            val (page, elements) = elementsOf(item.page, item.elements, ::missing)
            if (elements.any { it is DrawPdf || it is DrawTextMarkup }) {
                throw AgentToolException(AgentErrorCode.REFUSED, "A PDF page or a mark of its text is not placed: it goes with its page.")
            }
            val box = ElementItems.placedBox(page, elements, item)
            if (box != null && (box.left < 0f || box.top < 0f || box.right > page.widthMm || box.bottom > page.heightMm)) {
                throw AgentToolException(AgentErrorCode.REFUSED, "They would go outside the page, which is ${page.widthMm} by ${page.heightMm} millimeters.")
            }
            null
        }
        is ProposalItem.ReorderElements -> {
            elementsOf(item.page, item.elements, ::missing)
            null
        }
        is ProposalItem.RestyleText -> (findText(item.text) ?: missing("text \"${item.text.take(64)}\"")).second.text
        is ProposalItem.UpdateText -> {
            val (page, text) = findText(item.text) ?: missing("text \"${item.text.take(64)}\"")
            val left = item.x ?: text.x
            val top = item.y ?: text.y
            if (left < 0f || top < 0f || top >= page.heightMm || left + (item.width ?: text.width) > page.widthMm) {
                throw AgentToolException(AgentErrorCode.REFUSED, "It would go outside the page, which is ${page.widthMm} by ${page.heightMm} millimeters.")
            }
            text.text
        }
        is ProposalItem.AddStroke -> {
            val page = pageById(item.page) ?: missing("page \"${item.page.take(64)}\"")
            if (item.points.any { (x, y) -> x < 0f || y < 0f || x > page.widthMm || y > page.heightMm }) {
                throw AgentToolException(AgentErrorCode.REFUSED, "A point is outside the page, which is ${page.widthMm} by ${page.heightMm} millimeters.")
            }
            null
        }
        is ProposalItem.RestyleStroke -> {
            ElementItems.findStroke(this, item.stroke) ?: missing("stroke \"${item.stroke.take(64)}\"")
            null
        }
        is ProposalItem.AddImage -> {
            if (item.page != null && pageById(item.page) == null) missing("page \"${item.page.take(64)}\"")
            null
        }
        is ProposalItem.RemovePage -> {
            pageById(item.page) ?: missing("page \"${item.page.take(64)}\"")
            null
        }
        is ProposalItem.MovePage -> {
            pageById(item.page) ?: missing("page \"${item.page.take(64)}\"")
            if (item.after != null) {
                pageById(item.after) ?: missing("page \"${item.after.take(64)}\"")
                if (item.after == item.page) throw AgentToolException(AgentErrorCode.REFUSED, "A page is not moved after itself.")
            }
            null
        }
        is ProposalItem.DuplicatePage -> {
            pageById(item.page) ?: missing("page \"${item.page.take(64)}\"")
            if (item.after != null) pageById(item.after) ?: missing("page \"${item.after.take(64)}\"")
            null
        }
        is ProposalItem.SetPageFormat -> {
            pageById(item.page) ?: missing("page \"${item.page.take(64)}\"")
            null
        }
        is ProposalItem.RenameDocument -> title
    }
}

/** The index of the page [item] is on, for an item on a page or its elements. */
internal fun DrawDocument.pageIndexOf(item: ProposalItem): Int? {
    val id = when (item) {
        is ProposalItem.RemoveElements -> item.page
        is ProposalItem.PlaceElements -> item.page
        is ProposalItem.ReorderElements -> item.page
        is ProposalItem.AddStroke -> item.page
        is ProposalItem.AddImage -> item.page
        is ProposalItem.RemovePage -> item.page
        is ProposalItem.MovePage -> item.page
        is ProposalItem.DuplicatePage -> item.page
        is ProposalItem.SetPageFormat -> item.page
        is ProposalItem.RestyleText -> findText(item.text)?.first?.id
        is ProposalItem.UpdateText -> findText(item.text)?.first?.id
        is ProposalItem.RestyleStroke -> ElementItems.findStroke(this, item.stroke)?.first?.id
        else -> null
    } ?: return null
    return pages.indexOfFirst { it.id == id }.takeIf { it >= 0 }
}

/** The page [pageId] and its elements [refs]; fails when one is not there. */
internal fun DrawDocument.elementsOf(pageId: String, refs: List<ElementRef>, missing: (String) -> Nothing): Pair<DrawPage, List<DrawElement>> {
    val page = pageById(pageId) ?: missing("page \"${pageId.take(64)}\"")
    val shown = page.elements().filter { it.removed == null }.associateBy { it.id }
    return page to refs.map { shown[it.id] ?: missing("element \"${it.id.take(64)}\" on this page") }
}

/** The page named by the arguments of a tool: by its id, by its index, or the first one. */
internal fun DrawDocument.pageOf(arguments: JsonObject): DrawPage {
    val id = arguments.string("page")
    val index = arguments.int("index")
    val page = when {
        id != null -> pages.firstOrNull { it.id == id }
        index != null -> pages.getOrNull(index)
        else -> pages.firstOrNull()
    }
    return page ?: throw AgentToolException(AgentErrorCode.NOT_FOUND, "There is no such page.")
}
