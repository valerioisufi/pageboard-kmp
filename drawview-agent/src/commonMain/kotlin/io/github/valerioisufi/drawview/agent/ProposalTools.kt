package io.github.valerioisufi.drawview.agent

import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.geometry.Rect
import io.github.valerioisufi.drawview.store.OpenDocument
import io.github.valerioisufi.drawview.store.OpenDocuments
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The tools that propose changes to the documents, tell how they went and undo them. */
internal class ProposalTools(
    documents: OpenDocuments,
    access: AgentAccess,
    engines: AgentEngines,
    planner: ProposalPlanner,
    private val proposals: ProposalBook,
    private val submit: suspend (Proposal, review: Boolean) -> Unit,
    private val limits: ProposalLimits,
    private val library: LibraryBook,
    private val activity: AgentActivity,
    private val undo: suspend (batchId: String) -> UndoOutcome
) : ToolGroup(documents, access, engines, planner) {
    /** A plan takes time on the thread of the document: one at a time, whoever asks. */
    private val planning = Mutex()

    fun previewProposal() = AgentTool(
        name = "preview_proposal",
        description = "What a proposal would do, without proposing it: nothing changes and the user is not asked. For each item: where its text would go (page, box in millimeters), its font size, the pages it would add and warnings: far_from_anchor, moved_to_new_page, split, narrowed, overlaps, past_area, outside_page, past_page_end, chain_overflow. An item that moves or resizes elements answers their boxes as they would be. When measured is false the heights are estimates. The place is computed again when the user accepts.",
        inputSchema = schema(required = listOf("document", "items")) {
            string("document", "The id of the document.")
            putJsonObject("items") { itemsSchema() }
        }
    ) { arguments, _ ->
        val documentId = arguments.requiredString("document")
        val items = arguments.items()
        limits.checkItems(items)
        if (!limits.canPlan(items)) throw AgentToolException(AgentErrorCode.REFUSED, "This is too much text to preview at once: use fewer items, or propose it.")
        checkNames(documentId, items)
        value(proposalJson.encodeToJsonElement(withDocument(documentId) { open -> planOf(open, withImages(open, items, keep = false)) }))
    }

    fun propose() = AgentTool(
        name = "propose",
        description = "Proposes changes to a document. Answers the id of the proposal, to follow with proposal_status, what it would do as preview_proposal says, and mode: review (the user reviews it, and nothing changes until then) or applied (the user chose to apply changes at once: it is done already). With require_review true it waits for the user in any case. What is removed stays hidden in the document, and every change can be undone by the user.",
        inputSchema = schema(required = listOf("document", "title", "items")) {
            string("document", "The id of the document.")
            string("title", "What the proposal does, in a few words, for the user.")
            putJsonObject("items") { itemsSchema() }
            boolean("require_review", REQUIRE_REVIEW)
        }
    ) { arguments, connection ->
        val documentId = arguments.requiredString("document")
        val title = arguments.requiredString("title")
        limits.check(title, arguments.items())
        val items = withDocument(documentId) { open -> withImages(open, arguments.items(), keep = true) }
        checkNames(documentId, items)
        var plan: PlanAnswer? = null
        val proposal = withDocument(documentId) { open ->
            val entries = open.edit { session ->
                if (session.isReadOnly) throw AgentToolException(AgentErrorCode.REFUSED, "This document may only be read on this device.")
                items.map { ProposalEntry(it, session.document.beforeOf(it), page = session.document.pageIndexOf(it)) }
                    .also { checkQuotes(session.document, items) }
            }
            if (limits.canPlan(items)) plan = planOf(open, items)
            val name = access.connections.value.firstOrNull { it.id == connection }?.name ?: "A client"
            proposals.add(documentId, name, title.trim(), entries)
        }
        val review = arguments.boolean("require_review") ?: false
        submit(proposal, review)
        value(proposalJson.encodeToJsonElement(proposals.find(proposal.id)!!.toStatus().copy(plan = plan, mode = modeOf(review))))
    }

    /**
     * [items] with their images read by the application ([keep]: kept among the files of the documents),
     * each one given its page and the box it goes in.
     */
    private suspend fun withImages(open: OpenDocument, items: List<ProposalItem>, keep: Boolean): List<ProposalItem> {
        if (items.none { it is ProposalItem.AddImage }) return items
        val images = engines.images ?: throw AgentToolException(AgentErrorCode.UNAVAILABLE, "This application does not add images.")
        return items.map { item ->
            if (item !is ProposalItem.AddImage) return@map item
            val read = (if (keep) images.keep(item.path) else images.read(item.path))
                ?: throw AgentToolException(AgentErrorCode.REFUSED, "\"${item.path.take(200)}\" is not an image file this application reads, or is too large.")
            open.edit { session ->
                val document = session.document
                val page = (item.page?.let { id -> document.pageById(id) } ?: document.pages.lastOrNull())
                    ?: throw AgentToolException(AgentErrorCode.NOT_FOUND, "There is no page \"${item.page?.take(64)}\" in this document: read it again.")
                val box = imageBox(document, page, read, item.area)
                item.copy(path = read.path, page = page.id, area = AgentArea(box.left, box.top, box.width, box.height), hash = read.hash)
            }
        }
    }

    /** Where an image of [image]'s proportions goes on [page]: fitted in [area], or in its largest free area. */
    private fun imageBox(document: DrawDocument, page: DrawPage, image: AgentImage, area: AgentArea?): Rect {
        val ratio = if (image.widthPx > 0 && image.heightPx > 0) image.heightPx.toFloat() / image.widthPx else 1f
        if (area != null) {
            if (!area.isInside(page.widthMm, page.heightMm)) {
                throw AgentToolException(AgentErrorCode.REFUSED, "area is outside the page, which is ${page.widthMm} by ${page.heightMm} millimeters.")
            }
            return fitted(area.rect, ratio)
        }
        val free = planner.geometryOf(document, page).freeAreas.firstOrNull()
            ?: Rect(page.widthMm * 0.1f, page.heightMm * 0.1f, page.widthMm * 0.9f, page.heightMm * 0.9f)
        val room = Rect(free.left, free.top, free.left + minOf(free.width, DEFAULT_IMAGE_WIDTH_MM), free.bottom)
        return fitted(room, ratio)
    }

    /** The largest box of height [ratio] times its width in [room], at its top-left corner. */
    private fun fitted(room: Rect, ratio: Float): Rect {
        val width = minOf(room.width, room.height / ratio)
        return Rect(room.left, room.top, room.left + width, room.top + width * ratio)
    }

    /** Fails when a passage to mark is not in the PDF text of its page. */
    private fun checkQuotes(document: DrawDocument, items: List<ProposalItem>) {
        for (item in items.filterIsInstance<ProposalItem.AddMarkup>()) {
            val page = document.pageById(item.page) ?: continue
            if (MarkupItems.find(document, page, item.quote, item.occurrence, engines) == null) {
                val times = if (item.occurrence > 1) " ${item.occurrence} times" else ""
                throw AgentToolException(
                    AgentErrorCode.NOT_FOUND,
                    "\"${item.quote.take(80)}\" is not in the PDF text of this page$times: quote it as read_page gives it in pdf_text."
                )
            }
        }
    }

    /** Refuses a new title another document of its folder has. */
    private suspend fun checkNames(documentId: String, items: List<ProposalItem>) {
        for (item in items.filterIsInstance<ProposalItem.RenameDocument>()) {
            if (!documents.store.isDocumentNameAvailable(documentId, item.title.trim())) {
                throw AgentToolException(AgentErrorCode.REFUSED, "Another document of its folder is named \"${item.title.trim().take(200)}\".")
            }
        }
    }

    /** What [items] would do to the document of [open] now; fails when what they refer to is not there. */
    private suspend fun planOf(open: OpenDocument, items: List<ProposalItem>): PlanAnswer = planning.withLock {
        open.edit { session ->
            items.forEach { session.document.beforeOf(it) }
            checkQuotes(session.document, items)
            planner.plan(session.document, items).toAnswer(items)
        }
    }

    private fun JsonObject.items(): List<ProposalItem> = try {
        proposalJson.decodeFromJsonElement(ListOfItems, this["items"] ?: throw SerializationException("no items"))
    } catch (e: SerializationException) {
        throw AgentToolException(AgentErrorCode.BAD_REQUEST, "The items cannot be read: ${e.message?.take(200)}")
    } catch (e: IllegalArgumentException) {
        throw AgentToolException(AgentErrorCode.BAD_REQUEST, "The items cannot be read: ${e.message?.take(200)}")
    }

    /** The schema of the items of a proposal. */
    private fun JsonObjectBuilder.itemsSchema() {
        put("type", "array")
        put("description", "What to do, in order. Text is the Markdown of the notebook, with LaTeX formulas between $ signs; a numbered list in a style of its own has two spaces after its markers, as in `a.  text`, `I.  text`, `1)  text`; a checklist is `- [ ] to do`, `- [x] done`.")
        putJsonObject("items") {
            put("type", "object")
            put("description", "kind \"replace_text\" (text, base, markdown): replaces a text box, with the base read_page gave. kind \"remove_text\" (text, base): removes one. kind \"insert_text\" (markdown, page?, new_page?, near?, near_area?, where?, area?, font_size?, color?, width?, style_from?): adds text in the free space of a page (default: the last one), going on onto new pages; new_page puts it on a new page after it (after the page of near, when given); near is the id of the text box it is about, near_area ({x, y, width, height} in millimeters, with page) the area it is about, and where (below, the default, or margin) how to go next to it. where inside, with page and area ({x, y, width, height}), puts it in that area instead, at its top-left corner and as wide as it, whatever is there: the answer warns with past_area when it is higher than the area and overlaps when it covers something. The application computes the place otherwise, and the size from the page unless you give font_size (points); color is the blue of the models unless given; width (millimeters) is the width of its boxes, never made narrower; style_from is the id of a text box whose size, color and alignment it takes; alignment is start, center or end. Several new pages after the same page keep the order of their items. kind \"add_page\" (after?): adds a page. kind \"remove_elements\" (page, elements: [{id, base}]): removes elements of any kind from a page. kind \"place_elements\" (page, elements, dx?, dy?, scale?, rotation?, to_page?): moves them by dx and dy millimeters, scales them around the top-left corner of the box holding them and turns them by rotation degrees (clockwise) around its center, staying on the page; to_page puts them on that page instead, at the same place (not a PDF page or a mark of its text). kind \"reorder_elements\" (page, elements, to: front or back): puts them over or under the others. kind \"restyle_text\" (text, base, color?, font_size?, alignment?: start, center or end): changes the style of a text box. kind \"update_text\" (text, base, markdown?, font_size?, color?, alignment?, width?, x?, y?, rotation?, auto_width?): changes a text box in any way with one item: its words, its style, the width its lines wrap on, its top-left corner, in millimeters, and its rotation in degrees; auto_width true makes its box as wide as its longest line again; what is not given stays as it is. kind \"add_stroke\" (page, points: [[x, y], ...] in millimeters, color?, width? in millimeters, tool?: pressure_pen, marker, highlighter or dashed_line): draws a stroke, in blue by default. kind \"restyle_stroke\" (stroke, base, color?, width?): changes the color or width of a stroke, which gets a new id. kind \"add_image\" (path, page?, area?): adds an image file of this computer, in area (its proportions kept) or in the free space of the page. kind \"remove_page\" (page, base): removes a page, to the trash of the pages; a document keeps one page. kind \"move_page\" (page, after?): moves a page after another, or first. kind \"duplicate_page\" (page, after?): adds a copy of a page with all it holds, after it by default. kind \"set_page_format\" (page, base, width_mm?, height_mm?, background?: plain, ruled, grid, dotted or document, spacing_mm?, background_color?): changes the size or the background of a page. kind \"rename_document\" (title): renames the document. kind \"set_document_defaults\" (width_mm?, height_mm?, background?: plain, ruled, grid or dotted, spacing_mm?, background_color?): changes the format a new page takes, and the background of the pages that have the one of the document. kind \"restore_pages\" (pages: [id]): puts pages of the trash of the pages (list_removed_pages) back where they were. kind \"add_shape\" (page, shape_kind: line, arrow, double_arrow, rectangle, rounded_rectangle, ellipse, triangle, right_triangle, diamond, pentagon or hexagon, x?, y?, width?, height?, rotation?, from?, to?, color?, fill?, width_mm?, outline?: solid, dashed or dotted, start_head?, end_head?: none, open or filled): draws a shape, a shape of a box in {x, y, width, height} millimeters turned by rotation degrees, a line from [x, y] to [x, y] (an arrow has its head at to), in blue with no fill by default. kind \"update_shape\" (shape, base, shape_kind?, color?, fill?: a color or none, width_mm?, outline?, start_head?, end_head?, from?, to?): changes a shape of read_page (its custom_elements of type drawview.shape): its style, its shape_kind (a line stays a line), the ends of a line; place_elements moves, scales and turns it. kind \"add_markup\" (page, quote, markup_kind: highlight, underline or strikeout, occurrence?, color?): marks a passage of the PDF text of the page (pdf_text of read_page), its occurrence-th one (1 by default); spaces and line breaks count as one space. kind \"restyle_markups\" (page, markups: [{id, base}], markup_kind?, color?): changes marks of read_page; remove_elements removes them. Colors are #RRGGBB or #RRGGBBAA; the bases are those read_page gave.")
            putJsonObject("properties") {
                putJsonObject("kind") {
                    put("type", "string")
                    putJsonArray("enum") {
                        add(JsonPrimitive("replace_text"))
                        add(JsonPrimitive("remove_text"))
                        add(JsonPrimitive("insert_text"))
                        add(JsonPrimitive("add_page"))
                        add(JsonPrimitive("remove_elements"))
                        add(JsonPrimitive("place_elements"))
                        add(JsonPrimitive("reorder_elements"))
                        add(JsonPrimitive("restyle_text"))
                        add(JsonPrimitive("update_text"))
                        add(JsonPrimitive("add_stroke"))
                        add(JsonPrimitive("restyle_stroke"))
                        add(JsonPrimitive("add_image"))
                        add(JsonPrimitive("remove_page"))
                        add(JsonPrimitive("move_page"))
                        add(JsonPrimitive("duplicate_page"))
                        add(JsonPrimitive("set_page_format"))
                        add(JsonPrimitive("rename_document"))
                        add(JsonPrimitive("set_document_defaults"))
                        add(JsonPrimitive("restore_pages"))
                        add(JsonPrimitive("add_shape"))
                        add(JsonPrimitive("update_shape"))
                        add(JsonPrimitive("add_markup"))
                        add(JsonPrimitive("restyle_markups"))
                    }
                }
            }
            putJsonArray("required") { add(JsonPrimitive("kind")) }
        }
    }

    fun proposalStatus() = AgentTool(
        name = "proposal_status",
        description = "What became of a proposal: waiting, or the outcome of each item: accepted, rejected, or stale when what it was made on changed after you read it (read it again and propose again). Once decided, for each item, placed gives the boxes where its text or its elements went, each with the id of its element, and changed the elements it made or changed, each with its id and the base to change it again by, without reading its page again.",
        inputSchema = schema(required = listOf("proposal")) { string("proposal", "The id answered by propose.") }
    ) { arguments, _ ->
        val id = arguments.requiredString("proposal")
        library.find(id)?.let { return@AgentTool value(proposalJson.encodeToJsonElement(it.toStatus())) }
        val proposal = proposals.find(id)
            ?: throw AgentToolException(AgentErrorCode.NOT_FOUND, "There is no such proposal. Only the most recent ones are kept: this one may have been applied already, so read the document again before proposing again.")
        if (!access.allows(proposal.documentId)) {
            throw AgentToolException(AgentErrorCode.NOT_GRANTED, "The document of this proposal is no longer allowed.")
        }
        value(proposalJson.encodeToJsonElement(proposal.toStatus()))
    }

    fun listActivity() = AgentTool(
        name = "list_activity",
        description = "What the models changed, the most recent first, of the documents you may reach (and of the library when all are allowed): id (to undo it), document (none: the library), the client, the title of its proposal, how many items, when (milliseconds), whether it was undone; undoable false: it can no longer be undone.",
        inputSchema = schema()
    ) { _, _ ->
        val grants = access.grants.value
        val batches = activity.batches.value.asReversed()
            .filter { batch -> batch.documentId?.let { grants.allows(it) } ?: grants.allDocuments }
            .map { ActivityEntry(it.id, it.documentId, it.client, it.title, it.items, it.at, it.undone, it.undoable.takeIf { able -> !able }) }
        value(buildJsonObject { put("batches", json.encodeToJsonElement(batches)) })
    }

    fun undoBatch() = AgentTool(
        name = "undo",
        description = "Undoes a batch of list_activity: done only while what it changed was not changed again since, by the user or anyone else; otherwise nothing is done and the answer is refused.",
        inputSchema = schema(required = listOf("batch")) { string("batch", "The id list_activity gave.") }
    ) { arguments, _ ->
        val id = arguments.requiredString("batch")
        val batch = activity.find(id) ?: throw AgentToolException(AgentErrorCode.NOT_FOUND, "There is no such batch: only the most recent ones are kept.")
        val grants = access.grants.value
        if (batch.documentId?.let { !grants.allows(it) } ?: !grants.allDocuments) {
            throw AgentToolException(AgentErrorCode.NOT_GRANTED, "The user has not allowed what this batch changed.")
        }
        when (undo(id)) {
            UndoOutcome.UNDONE -> value(buildJsonObject { put("batch", id); put("state", "undone") })
            UndoOutcome.CHANGED -> throw AgentToolException(AgentErrorCode.REFUSED, "What it changed was changed again since: nothing was undone, so that nothing is lost.")
            UndoOutcome.GONE -> throw AgentToolException(AgentErrorCode.NOT_FOUND, "This batch was undone already, or can no longer be undone.")
            UndoOutcome.UNAVAILABLE -> throw AgentToolException(AgentErrorCode.REFUSED, "Its document cannot be changed on this device.")
        }
    }
}
