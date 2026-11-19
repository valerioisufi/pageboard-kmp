package io.github.valerioisufi.pageboard.agent

import io.github.valerioisufi.pageboard.model.ImageElement
import io.github.valerioisufi.pageboard.model.PdfElement
import io.github.valerioisufi.pageboard.model.TextElement
import io.github.valerioisufi.pageboard.model.StrokePoint
import io.github.valerioisufi.pageboard.model.StrokeTool
import io.github.valerioisufi.pageboard.model.change.StrokeData
import io.github.valerioisufi.pageboard.model.newElementId
import io.github.valerioisufi.pageboard.model.PageBackground
import io.github.valerioisufi.pageboard.model.change.AddElements
import io.github.valerioisufi.pageboard.model.change.UpdateTexts
import io.github.valerioisufi.pageboard.model.change.toData
import io.github.valerioisufi.pageboard.model.geometry.Rect
import io.github.valerioisufi.pageboard.model.text.TextSizes
import io.github.valerioisufi.pageboard.store.InMemoryKeyValueStorage
import io.github.valerioisufi.pageboard.store.KeyValueDocumentStore
import io.github.valerioisufi.pageboard.store.OpenDocument
import io.github.valerioisufi.pageboard.store.OpenDocuments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Where the text of a model goes, and what the model and the user are told (docs/plans/agent-placement.md). */
class ProposalPlannerTest {

    private class Setup(val agents: ExternalAgents, val connection: String, val documentId: String, val open: OpenDocument, val engines: AgentEngines)

    private suspend fun setup(test: TestScope, scope: CoroutineScope, engines: AgentEngines = AgentEngines()): Setup {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = scope)
        val documents = OpenDocuments(store, StandardTestDispatcher(test.testScheduler), scope)
        val agents = ExternalAgents(documents, engines, store)
        val document = store.createDocument("Physics", 210f, 297f, PageBackground.Solid())
        val open = assertNotNull(documents.open(document.id))
        agents.access.setEnabled(true)
        agents.access.grant(document.id)
        return Setup(agents, agents.access.connect("test").id, document.id, open, engines)
    }

    private suspend fun Setup.addText(content: String, x: Float = 20f, y: Float = 40f, width: Float = 170f, pageIndex: Int = 0): TextElement =
        open.edit { session ->
            val page = session.document.pages[pageIndex]
            val text = TextElement().apply {
                text = content
                this.x = x; this.y = y; this.width = width; height = 10f; fontSize = 16f
            }
            session.submit(AddElements.of(page, listOf(text)))
            text
        }

    private suspend fun Setup.call(name: String, arguments: JsonObject): JsonObject =
        assertIs<ToolResult.Value>(agents.tools.call(name, arguments, connection)).json.jsonObject

    private suspend fun Setup.propose(vararg items: JsonObject): String = call(
        "propose",
        buildJsonObject {
            put("document", documentId)
            put("title", "A change")
            put("items", JsonArray(items.toList()))
        }
    ).getValue("proposal").jsonPrimitive.content

    private suspend fun Setup.plan(vararg items: ProposalItem): ProposalPlan =
        open.edit { session -> ProposalPlanner(engines).plan(session.document, items.toList()) }

    private fun near(id: String, markdown: String = "A note", where: String? = null) = buildJsonObject {
        put("kind", "insert_text")
        put("markdown", markdown)
        put("near", id)
        where?.let { put("where", it) }
    }

    private suspend fun Setup.pageId(index: Int = 0): String = open.edit { it.document.pages[index].id }

    private suspend fun Setup.boxOf(text: TextElement) = open.edit { text.getRotatedBounds() }

    @Test
    fun aTextGoesBelowTheBoxItIsAbout() = runTest {
        val setup = setup(this, backgroundScope)
        setup.addText("Top", y = 15f)
        val about = setup.addText("Gauss", y = 150f)

        val piece = setup.plan(ProposalItem.InsertText("A note", near = about.id)).items.single().pieces.single()

        val box = setup.boxOf(about)
        assertTrue(piece.box.top >= box.bottom, "below: ${piece.box}, $box")
        assertTrue(piece.box.top < box.bottom + 15f, "next to it: ${piece.box}, $box")
        assertEquals(about.x, piece.box.left)
    }

    @Test
    fun aTextGoesInTheMarginBesideTheBoxItIsAbout() = runTest {
        val setup = setup(this, backgroundScope)
        val about = setup.addText("Gauss", x = 15f, y = 150f, width = 90f)

        val piece = setup.plan(ProposalItem.InsertText("A note", near = about.id, place = InsertPlace.MARGIN)).items.single().pieces.single()

        val box = setup.boxOf(about)
        assertTrue(piece.box.left >= box.right, "beside: ${piece.box}, $box")
        assertTrue(piece.box.top < box.bottom && piece.box.bottom > box.top, "at its height: ${piece.box}, $box")
        assertTrue(piece.box.width <= 60f)
    }

    @Test
    fun aTextGoesNextToAnAreaOfThePage() = runTest {
        val setup = setup(this, backgroundScope)
        val area = AgentArea(30f, 100f, 100f, 40f)

        val piece = setup.plan(ProposalItem.InsertText("About the figure", page = setup.pageId(), nearArea = area)).items.single().pieces.single()

        assertTrue(piece.box.top >= 140f && piece.box.top < 155f || piece.box.left >= 130f, "next to the area: ${piece.box}")
    }

    @Test
    fun theNoteFollowsTheBoxMovedAfterItWasProposed() = runTest {
        val setup = setup(this, backgroundScope)
        val about = setup.addText("Gauss", y = 50f)
        val id = setup.propose(near(about.id))

        setup.open.edit { session ->
            val page = session.document.pages[0]
            session.submit(UpdateTexts(page.id, listOf(about.toData().copy(y = 200f))))
        }
        setup.agents.decide(id, setOf(0))

        val note = setup.open.document.pages[0].texts.single { it.text == "A note" }
        assertTrue(note.y >= 200f, "below the box where it is now: ${note.y}")
    }

    @Test
    fun aBoxGoneAfterItWasProposedMakesTheItemStale() = runTest {
        val setup = setup(this, backgroundScope)
        val about = setup.addText("Gauss")
        val base = setup.open.edit { it.document.findText(about.id)!!.second.base() }
        val id = setup.propose(near(about.id))

        val remove = setup.propose(buildJsonObject {
            put("kind", "remove_text")
            put("text", about.id)
            put("base", base)
        })
        setup.agents.decide(remove, setOf(0))
        setup.agents.decide(id, setOf(0))

        assertEquals(ItemOutcome.STALE, setup.agents.proposals.find(id)!!.entries.single().outcome)
        assertTrue(setup.open.document.pages[0].texts.none { it.text == "A note" && it.removed == null })
    }

    @Test
    fun whatMakesNoSenseIsRefused() = runTest {
        val setup = setup(this, backgroundScope)
        val about = setup.addText("Gauss")
        val page = setup.pageId()
        val base = setup.open.edit { it.document.findText(about.id)!!.second.base() }

        // A box that is not there
        assertEquals(AgentErrorCode.NOT_FOUND, assertFailsWith<AgentToolException> { setup.propose(near("nothing")) }.code)
        // near with a page, or with an area
        assertEquals(AgentErrorCode.REFUSED, assertFailsWith<AgentToolException> {
            setup.propose(buildJsonObject {
                put("kind", "insert_text")
                put("markdown", "A note")
                put("near", about.id)
                put("page", page)
            })
        }.code)
        // Going next to what the same proposal removes
        assertEquals(AgentErrorCode.REFUSED, assertFailsWith<AgentToolException> {
            setup.propose(
                buildJsonObject {
                    put("kind", "remove_text")
                    put("text", about.id)
                    put("base", base)
                },
                near(about.id)
            )
        }.code)
        // where without what it is about
        assertEquals(AgentErrorCode.REFUSED, assertFailsWith<AgentToolException> {
            setup.propose(buildJsonObject {
                put("kind", "insert_text")
                put("markdown", "A note")
                put("where", "margin")
            })
        }.code)
        // A way of going that does not exist
        assertEquals(AgentErrorCode.BAD_REQUEST, assertFailsWith<AgentToolException> { setup.propose(near(about.id, where = "above")) }.code)
        // An area without its page, and areas outside the page
        fun area(x: Float, width: Float, withPage: Boolean = true) = buildJsonObject {
            put("kind", "insert_text")
            put("markdown", "A note")
            if (withPage) put("page", page)
            putJsonObject("near_area") {
                put("x", x)
                put("y", 10f)
                put("width", width)
                put("height", 10f)
            }
        }
        assertEquals(AgentErrorCode.REFUSED, assertFailsWith<AgentToolException> { setup.propose(area(10f, 10f, withPage = false)) }.code)
        assertEquals(AgentErrorCode.REFUSED, assertFailsWith<AgentToolException> { setup.propose(area(205f, 10f)) }.code)
        assertEquals(AgentErrorCode.REFUSED, assertFailsWith<AgentToolException> { setup.propose(area(10f, -1f)) }.code)
    }

    @Test
    fun anItemSeesTheRoomLeftByTheOnesBeforeIt() = runTest {
        val setup = setup(this, backgroundScope)
        val first = setup.addText("At the top", y = 12f)
        val base = setup.open.edit { it.document.findText(first.id)!!.second.base() }

        val alone = setup.plan(ProposalItem.InsertText("New")).items.single().pieces.single()
        val planned = setup.plan(ProposalItem.RemoveText(first.id, base), ProposalItem.InsertText("New")).items[1].pieces.single()

        assertTrue(planned.box.top < alone.box.top, "the room of what was removed: ${planned.box}, ${alone.box}")
    }

    @Test
    fun anItemSeesTheRoomTakenByALongerTextBeforeIt() = runTest {
        val setup = setup(this, backgroundScope)
        val first = setup.addText("At the top", y = 12f)
        val base = setup.open.edit { it.document.findText(first.id)!!.second.base() }
        val long = (1..20).joinToString("\n") { "Line $it" }

        val plan = setup.plan(ProposalItem.ReplaceText(first.id, base, long), ProposalItem.InsertText("New"))

        val grown = plan.items[0].pieces.single().box
        val placed = plan.items[1].pieces.single().box
        assertTrue(placed.top >= grown.bottom, "below what it grew to: $placed, $grown")
    }

    @Test
    fun aNoteGoesNextToTheBoxAsAnEarlierItemLeavesIt() = runTest {
        val setup = setup(this, backgroundScope)
        val first = setup.addText("At the top", y = 12f)
        val base = setup.open.edit { it.document.findText(first.id)!!.second.base() }
        val long = (1..20).joinToString("\n") { "Line $it" }

        val plan = setup.plan(ProposalItem.ReplaceText(first.id, base, long), ProposalItem.InsertText("New", near = first.id))

        assertTrue(plan.items[1].pieces.single().box.top >= plan.items[0].pieces.single().box.bottom)
    }

    @Test
    fun aClosedDocumentCountsTheLinesThatWrap() = runTest {
        val setup = setup(this, backgroundScope)
        val long = setup.addText("word ".repeat(400), y = 15f, width = 170f)

        val placed = setup.plan(ProposalItem.InsertText("New")).items.single().pieces.single().box

        // One line in the source, many laid out: the height kept by the document counts one
        assertTrue(placed.top > long.y + 40f, "below its wrapped lines: $placed")
    }

    @Test
    fun aLongTextNextToABoxStaysBelowItsBeginningOnThatPage() = runTest {
        val setup = setup(this, backgroundScope)
        setup.addText("Top", y = 12f, width = 60f)
        val about = setup.addText("Gauss", y = 150f)
        val text = (1..200).joinToString("\n\n") { "Paragraph $it" }

        val pieces = setup.plan(ProposalItem.InsertText(text, near = about.id)).items.single().pieces

        val onPage = pieces.filter { !it.isOnNewPage }
        assertTrue(onPage.isNotEmpty())
        assertTrue(onPage.all { it.box.top >= 150f }, "in reading order: ${onPage.map { it.box }}")
        assertTrue(pieces.any { it.isOnNewPage })
    }

    // ==========================================================
    // What the model is told
    // ==========================================================

    private class Editors(private val inEditor: Boolean) : AgentEditors {
        var opened = 0
        override fun openForReview(documentId: String) { opened++ }
        override fun settle(documentId: String): Boolean = inEditor
        override fun isInEditor(documentId: String): Boolean = inEditor
        override fun textHeight(documentId: String, markdown: String, widthMm: Float, fontSize: Float): Float? =
            if (inEditor) markdown.lines().size * 5f else null
        override fun pdfLayout(documentId: String, pdf: io.github.valerioisufi.pageboard.model.PdfElement): io.github.valerioisufi.pageboard.model.PdfTextLayout? = null
    }

    private suspend fun Setup.preview(vararg items: JsonObject): JsonObject = call(
        "preview_proposal",
        buildJsonObject {
            put("document", documentId)
            put("items", JsonArray(items.toList()))
        }
    )

    private fun insert(markdown: String) = buildJsonObject {
        put("kind", "insert_text")
        put("markdown", markdown)
    }

    private fun lines(count: Int) = (1..count).joinToString("\n") { "Line $it" }

    /** As many lines as take [heightMm] at the size of the tests, without an editor. */
    private fun linesOf(heightMm: Float) = lines((heightMm / TextSizes.estimatedLineHeightMm(16f)).toInt())

    private suspend fun Setup.warningsOf(vararg items: ProposalItem) = plan(*items).items.last().warnings

    @Test
    fun aPreviewChangesNothingAndAsksNobody() = runTest {
        val editors = Editors(inEditor = false)
        val setup = setup(this, backgroundScope, AgentEngines(editors = editors))
        val text = setup.addText("Gauss", y = 20f)
        val operations = setup.open.edit { it.document.pages[0].texts.size }

        val answer = setup.preview(near(text.id))

        assertEquals(operations, setup.open.edit { it.document.pages[0].texts.size })
        assertTrue(setup.agents.proposals.proposals.value.isEmpty())
        assertEquals(0, editors.opened)
        val item = answer.getValue("items").jsonArray.single().jsonObject
        assertEquals("insert_text", item.getValue("kind").jsonPrimitive.content)
        assertEquals(16f, item.getValue("font_size").jsonPrimitive.float)
        assertEquals(setup.pageId(), item.getValue("pieces").jsonArray.single().jsonObject.getValue("page").jsonPrimitive.content)
    }

    @Test
    fun whatAPreviewSaysIsWhatIsApplied() = runTest {
        val setup = setup(this, backgroundScope)
        val text = setup.addText("Gauss", y = 150f)
        val long = (1..120).joinToString("\n\n") { "Paragraph $it" }

        val said = setup.preview(near(text.id, long)).getValue("items").jsonArray.single().jsonObject.getValue("pieces").jsonArray
        val id = setup.propose(near(text.id, long))
        setup.agents.decide(id, setOf(0))

        val placed = assertNotNull(setup.agents.proposals.find(id)).entries.single().placed
        assertEquals(said.size, placed.size)
        for ((piece, told) in placed.zip(said)) {
            val box = told.jsonObject
            assertEquals(piece.box.left, box.getValue("x").jsonPrimitive.float, 0.1f)
            assertEquals(piece.box.top, box.getValue("y").jsonPrimitive.float, 0.1f)
            assertEquals(piece.box.width, box.getValue("width").jsonPrimitive.float, 0.1f)
        }
        for (piece in placed) {
            val added = setup.open.document.pages.first { it.id == piece.pageId }.texts.single { it.id == piece.textId }
            assertEquals(piece.box.left, added.x)
            assertEquals(piece.box.top, added.y)
        }
    }

    @Test
    fun theStatusTellsWhereTheTextWent() = runTest {
        val setup = setup(this, backgroundScope)
        val id = setup.propose(insert("New"))

        val waiting = setup.call("proposal_status", buildJsonObject { put("proposal", id) })
        assertTrue("placed" !in waiting)

        setup.agents.decide(id, setOf(0))
        val status = setup.call("proposal_status", buildJsonObject { put("proposal", id) })
        val piece = status.getValue("placed").jsonArray.single().jsonArray.single().jsonObject
        val added = setup.open.document.pages[0].texts.single()
        assertEquals(added.id.isNotEmpty(), true)
        assertEquals(setup.pageId(), piece.getValue("page").jsonPrimitive.content)
        assertEquals(added.x, piece.getValue("x").jsonPrimitive.float, 0.1f)
    }

    @Test
    fun aProposalAnswersItsPlan() = runTest {
        val setup = setup(this, backgroundScope)
        val answer = setup.call(
            "propose",
            buildJsonObject {
                put("document", setup.documentId)
                put("title", "A change")
                put("items", JsonArray(listOf(insert("New"))))
            }
        )
        assertEquals(1, answer.getValue("plan").jsonObject.getValue("items").jsonArray.size)
    }

    @Test
    fun whetherTheHeightsAreMeasuredIsSaid() = runTest {
        val closed = setup(this, backgroundScope, AgentEngines(editors = Editors(inEditor = false)))
        assertEquals(false, closed.preview(insert("New")).getValue("measured").jsonPrimitive.boolean)
        val none = setup(this, backgroundScope)
        assertEquals(false, none.preview(insert("New")).getValue("measured").jsonPrimitive.boolean)
        val open = setup(this, backgroundScope, AgentEngines(editors = Editors(inEditor = true)))
        assertEquals(true, open.preview(insert("New")).getValue("measured").jsonPrimitive.boolean)
    }

    @Test
    fun aPreviewOfWhatIsNotThereFails() = runTest {
        val setup = setup(this, backgroundScope)
        assertEquals(AgentErrorCode.NOT_FOUND, assertFailsWith<AgentToolException> { setup.preview(near("nothing")) }.code)
        val other = setup(this, backgroundScope)
        other.open.edit { }
        other.agents.access.revoke(other.documentId)
        assertEquals(AgentErrorCode.NOT_GRANTED, assertFailsWith<AgentToolException> { other.preview(insert("New")) }.code)
    }

    @Test
    fun tooMuchTextIsNotPreviewedButCanBeProposed() = runTest {
        val setup = setup(this, backgroundScope)
        val items = (1..2).map { insert("x".repeat(20 * 1024)) }
        assertEquals(AgentErrorCode.REFUSED, assertFailsWith<AgentToolException> { setup.preview(*items.toTypedArray()) }.code)
        val answer = setup.call(
            "propose",
            buildJsonObject {
                put("document", setup.documentId)
                put("title", "A change")
                put("items", JsonArray(items))
            }
        )
        assertTrue("plan" !in answer)
    }

    @Test
    fun aTextFarFromWhatItIsAboutIsTold() = runTest {
        val setup = setup(this, backgroundScope)
        setup.addText(linesOf(204f), y = 60f)
        val about = setup.addText("At the end", y = 270f)

        val warning = setup.warningsOf(ProposalItem.InsertText("A note", near = about.id)).filterIsInstance<PlacementWarning.FarFromAnchor>().single()

        assertTrue(warning.byMm > 30f, "$warning")
    }

    @Test
    fun aTextThatDoesNotFitItsPageIsTold() = runTest {
        val setup = setup(this, backgroundScope)
        setup.addText(linesOf(270f), y = 12f, x = 12f, width = 186f)

        val warnings = setup.warningsOf(ProposalItem.InsertText("New", page = setup.pageId()))

        assertTrue(PlacementWarning.MovedToNewPage in warnings, "$warnings")
    }

    @Test
    fun aTextOnANewPageAskedForIsNotToldItMoved() = runTest {
        val setup = setup(this, backgroundScope)
        assertTrue(setup.warningsOf(ProposalItem.InsertText("New", newPage = true)).isEmpty())
    }

    @Test
    fun aTextInSeveralBoxesIsTold() = runTest {
        val setup = setup(this, backgroundScope)
        val long = (1..400).joinToString("\n\n") { "Paragraph $it" }
        val warning = setup.warningsOf(ProposalItem.InsertText(long)).filterIsInstance<PlacementWarning.Split>().single()
        assertTrue(warning.pieces > 1)
    }

    @Test
    fun aTextMadeNarrowForLackOfRoomIsTold() = runTest {
        val setup = setup(this, backgroundScope)
        setup.addText(linesOf(270f), y = 12f, x = 12f, width = 90f)

        val warning = setup.warningsOf(ProposalItem.InsertText("New")).filterIsInstance<PlacementWarning.Narrowed>().single()

        assertEquals(190f, warning.fromMm)
    }

    @Test
    fun aShortTextBelowATextIsAsWideAsThePageAndNotTold() = runTest {
        val setup = setup(this, backgroundScope)
        setup.addText("Old text", y = 20f, width = 100f)

        val planned = setup.plan(ProposalItem.InsertText("New")).items.single()

        assertEquals(190f, planned.pieces.single().box.width)
        assertTrue(planned.warnings.isEmpty(), "${planned.warnings}")
    }

    @Test
    fun aReplacedTextCoveringAnotherIsTold() = runTest {
        val setup = setup(this, backgroundScope)
        val text = setup.addText("Short", y = 20f)
        val below = setup.addText("Below", y = 60f)
        val base = setup.open.edit { it.document.findText(text.id)!!.second.base() }

        val warnings = setup.warningsOf(ProposalItem.ReplaceText(text.id, base, lines(30)))

        assertEquals(listOf(below.id), warnings.filterIsInstance<PlacementWarning.Overlaps>().single().with)
    }

    @Test
    fun whatAReplacedTextCoveredAlreadyIsNotItsDoing() = runTest {
        val setup = setup(this, backgroundScope)
        val text = setup.addText("One\nTwo\nThree\nFour\nFive\nSix", y = 20f)
        setup.addText("Under it", y = 25f)
        val base = setup.open.edit { it.document.findText(text.id)!!.second.base() }

        assertTrue(setup.warningsOf(ProposalItem.ReplaceText(text.id, base, lines(8))).none { it is PlacementWarning.Overlaps })
    }

    @Test
    fun aReplacedTextPassingTheEndOfThePageIsTold() = runTest {
        val setup = setup(this, backgroundScope)
        val text = setup.addText("Short", y = 270f)
        val base = setup.open.edit { it.document.findText(text.id)!!.second.base() }

        val warning = setup.warningsOf(ProposalItem.ReplaceText(text.id, base, lines(10))).filterIsInstance<PlacementWarning.PastPageEnd>().single()

        assertTrue(warning.byMm > 0f)
    }

    @Test
    fun aBoxOfAChainThatNoLongerFitsIsTold() = runTest {
        val setup = setup(this, backgroundScope)
        setup.open.edit { session -> session.document.addPage() }
        val second = setup.addText("Goes on", y = 12f, pageIndex = 1)
        val first = setup.open.edit { session ->
            val page = session.document.pages[0]
            val text = TextElement().apply {
                text = "Short"
                x = 20f; y = 270f; width = 170f; height = 10f; fontSize = 16f
                next = second.id
            }
            session.submit(AddElements.of(page, listOf(text)))
            text
        }
        val base = setup.open.edit { it.document.findText(first.id)!!.second.base() }

        val warnings = setup.warningsOf(ProposalItem.ReplaceText(first.id, base, lines(10)))

        assertTrue(warnings.any { it is PlacementWarning.ChainOverflow }, "$warnings")
        assertTrue(warnings.none { it is PlacementWarning.PastPageEnd }, "$warnings")
    }

    // ==========================================================
    // What the model reads
    // ==========================================================

    private suspend fun Setup.readPage(): JsonObject = call("read_page", buildJsonObject { put("document", documentId) })

    private fun JsonObject.number(name: String) = getValue(name).jsonPrimitive.float

    @Test
    fun anEmptyPageIsFreeInsideItsMargins() = runTest {
        val setup = setup(this, backgroundScope)
        val page = setup.readPage()
        val area = page.getValue("free_areas").jsonArray.single().jsonObject
        assertEquals(10f, area.number("x"))
        assertEquals(10f, area.number("y"))
        assertEquals(190f, area.number("width"))
        assertEquals(276f, area.number("height"))
        assertTrue("ink_area" !in page)
        assertTrue(page.getValue("images").jsonArray.isEmpty())
    }

    @Test
    fun theGeometryOfAPageIsRead() = runTest {
        val setup = setup(this, backgroundScope)
        setup.addText("Gauss", y = 100f)
        val image = setup.open.edit { session ->
            val page = session.document.pages[0]
            val image = ImageElement().apply {
                resourceId = "picture"
                x = 30f; y = 200f; width = 50f; height = 40f
            }
            session.submit(AddElements.of(page, listOf(image)))
            image
        }

        val page = setup.readPage()

        val text = page.getValue("texts").jsonArray.single().jsonObject
        assertEquals(16f, text.number("font_size"))
        assertTrue(text.number("height") > 0f)
        val read = page.getValue("images").jsonArray.single().jsonObject
        assertEquals(image.id, read.getValue("id").jsonPrimitive.content)
        assertEquals(40f, read.number("height"))
        val areas = page.getValue("free_areas").jsonArray.map { it.jsonObject }
        assertTrue(areas.size > 1)
        assertTrue(areas.none { it.number("y") < 240f && it.number("y") + it.number("height") > 200f && it.number("x") < 80f && it.number("x") + it.number("width") > 30f }, "$areas")
    }

    @Test
    fun aTextIsReadAtTheHeightOfItsWrappedLines() = runTest {
        val setup = setup(this, backgroundScope)
        setup.addText("word ".repeat(400), y = 15f)

        val text = setup.readPage().getValue("texts").jsonArray.single().jsonObject

        assertTrue(text.number("height") > 40f, "$text")
    }

    @Test
    fun theHeightOfAnEditorIsReadAsItIs() = runTest {
        val setup = setup(this, backgroundScope, AgentEngines(editors = Editors(inEditor = true)))
        val text = setup.addText("word ".repeat(400), y = 15f)
        setup.open.edit { text.height = 33f }

        val page = setup.readPage()

        assertEquals("true", page.getValue("measured").jsonPrimitive.content)
        assertEquals(33f, page.getValue("texts").jsonArray.single().jsonObject.number("height"))
    }

    @Test
    fun aPdfPageWithoutTextIsTakenWhole() = runTest {
        val setup = setup(this, backgroundScope)
        setup.open.edit { session ->
            val page = session.document.pages[0]
            session.submit(AddElements.of(page, listOf(PdfElement().apply { resourceId = "scan" })))
        }

        val page = setup.readPage()

        assertEquals(1, page.getValue("pdf_pages").jsonPrimitive.int)
        assertTrue(page.getValue("free_areas").jsonArray.isEmpty())
    }

    @Test
    fun handwritingIsReadAsAnArea() = runTest {
        val setup = setup(this, backgroundScope)
        setup.open.edit { session ->
            val page = session.document.pages[0]
            val stroke = StrokeData(
                id = newElementId(),
                points = (0 until 20).map { StrokePoint(x = 20f + it * 5f, y = 120f + (it % 2), pressure = 0.5f, timestampMs = it * 8L) },
                tool = StrokeTool.PRESSURE_PEN,
                size = 0.5f,
                color = 0xFF000000.toInt()
            ).toElement()
            session.submit(AddElements.of(page, listOf(stroke)))
        }

        val area = setup.readPage().getValue("ink_area").jsonObject

        assertTrue(area.number("x") <= 20f && area.number("y") <= 120f && area.number("width") >= 90f, "$area")
    }

    // ==========================================================
    // The place chosen by the user
    // ==========================================================

    @Test
    fun aPieceMovedBackFromANewPageAddsNoPage() = runTest {
        val setup = setup(this, backgroundScope)
        val long = (1..25).joinToString("\n\n") { "Paragraph $it" }
        val item = ProposalItem.InsertText(long)
        val page = setup.pageId()
        val pieces = setup.plan(item).items.single().pieces
        assertTrue(pieces.last().isOnNewPage)

        val chosen = mapOf(0 to pieces.map { ChosenPlace(page, it.box) })
        val planned = setup.open.edit { ProposalPlanner().plan(it.document, listOf(item), chosen) }.items.single()

        assertEquals(0, planned.pagesAdded)
        assertTrue(planned.pieces.all { it.pageId == page && it.next == null })
    }

    @Test
    fun aPieceLeftOnANewPageGoesOnFromTheOneBefore() = runTest {
        val setup = setup(this, backgroundScope)
        val long = (1..25).joinToString("\n\n") { "Paragraph $it" }
        val item = ProposalItem.InsertText(long)
        val pieces = setup.plan(item).items.single().pieces

        val moved = Rect(30f, 40f, 180f, 60f)
        val page = setup.pageId()
        val chosen = mapOf(0 to pieces.mapIndexed { index, piece ->
            when {
                index == 0 -> ChosenPlace(page, moved)
                piece.isOnNewPage -> ChosenPlace(null, piece.box)
                else -> ChosenPlace(page, piece.box)
            }
        })
        val planned = setup.open.edit { ProposalPlanner().plan(it.document, listOf(item), chosen) }.items.single()

        assertEquals(1, planned.pagesAdded)
        assertEquals(moved, planned.pieces[0].box)
        val last = planned.pieces.lastIndex
        assertEquals(planned.pieces[last].textId, planned.pieces[last - 1].next)
    }
}
