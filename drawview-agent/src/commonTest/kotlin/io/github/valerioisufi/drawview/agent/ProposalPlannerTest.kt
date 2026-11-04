package io.github.valerioisufi.drawview.agent

import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.model.change.UpdateTexts
import io.github.valerioisufi.drawview.model.change.toData
import io.github.valerioisufi.drawview.store.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.store.KeyValueDocumentStore
import io.github.valerioisufi.drawview.store.OpenDocument
import io.github.valerioisufi.drawview.store.OpenDocuments
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Where the text of a model goes, and what the model and the user are told (docs/agent-placement.md). */
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

    private suspend fun Setup.addText(content: String, x: Float = 20f, y: Float = 40f, width: Float = 170f, pageIndex: Int = 0): DrawText =
        open.edit { session ->
            val page = session.document.pages[pageIndex]
            val text = DrawText().apply {
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

    private suspend fun Setup.boxOf(text: DrawText) = open.edit { text.getRotatedBounds() }

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
}
