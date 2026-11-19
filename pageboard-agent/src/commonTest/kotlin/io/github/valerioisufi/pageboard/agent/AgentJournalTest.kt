package io.github.valerioisufi.pageboard.agent

import io.github.valerioisufi.pageboard.model.TextElement
import io.github.valerioisufi.pageboard.model.PageBackground
import io.github.valerioisufi.pageboard.model.change.AddElements
import io.github.valerioisufi.pageboard.model.change.UpdateTexts
import io.github.valerioisufi.pageboard.model.change.toData
import io.github.valerioisufi.pageboard.store.InMemoryKeyValueStorage
import io.github.valerioisufi.pageboard.store.KeyValueDocumentStore
import io.github.valerioisufi.pageboard.store.OpenDocument
import io.github.valerioisufi.pageboard.store.OpenDocuments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The proposals and the activity kept across two runs of an application (see `docs/plans/agent-ergonomics.md`). */
class AgentJournalTest {

    /** A journal in memory: what is kept, and what was put aside. */
    private class FakeJournal(var kept: String? = null) : AgentJournal {
        val aside = mutableListOf<String>()
        var saves = 0

        override suspend fun load(): String? = kept

        override suspend fun save(text: String) {
            kept = text
            saves++
        }

        override suspend fun setAside() {
            kept?.let { aside += it }
            kept = null
        }
    }

    private class Run(val agents: ExternalAgents, val connection: String, val open: OpenDocument, val documents: OpenDocuments)

    private class World(val store: KeyValueDocumentStore, val documentId: String, val journal: FakeJournal, val test: TestScope, val scope: CoroutineScope)

    private suspend fun world(test: TestScope, scope: CoroutineScope, journal: FakeJournal = FakeJournal()): World {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = scope)
        val document = store.createDocument("Physics", 210f, 297f, PageBackground.Solid())
        return World(store, document.id, journal, test, scope)
    }

    /** The application started on the notes of [this]: what its journal kept is back. */
    private suspend fun World.start(): Run {
        val documents = OpenDocuments(store, StandardTestDispatcher(test.testScheduler), scope)
        val agents = ExternalAgents(documents, AgentEngines(), store, journal)
        agents.restore()
        agents.access.setEnabled(true)
        agents.access.setAllDocuments(true)
        return Run(agents, agents.access.connect("test").id, assertNotNull(documents.open(documentId)), documents)
    }

    /** The application quits: its documents are stored. */
    private suspend fun Run.quit() {
        open.edit { }
        documents.close(open)
    }

    private suspend fun Run.call(name: String, arguments: JsonObject = JsonObject(emptyMap())): JsonObject =
        assertIs<ToolResult.Value>(agents.tools.call(name, arguments, connection)).json.jsonObject

    private suspend fun Run.propose(documentId: String, vararg items: JsonObject): String = call(
        "propose",
        buildJsonObject {
            put("document", documentId)
            put("title", "A change")
            put("items", JsonArray(items.toList()))
        }
    ).getValue("proposal").jsonPrimitive.content

    private fun insert(markdown: String) = buildJsonObject {
        put("kind", "insert_text")
        put("markdown", markdown)
    }

    private fun Run.texts(): List<String> = open.document.pages.flatMap { page -> page.texts.filter { it.removed == null }.map { it.text } }

    @Test
    fun aProposalWaitingIsAcceptedAfterTheApplicationWasRestarted() = runTest {
        val world = world(this, backgroundScope)
        val first = world.start()
        val id = first.propose(world.documentId, insert("Kept"))
        first.quit()

        val second = world.start()
        val status = second.call("proposal_status", buildJsonObject { put("proposal", id) })
        assertEquals("waiting", status.getValue("state").jsonPrimitive.content)
        second.agents.decide(id, setOf(0))
        assertEquals(listOf("Kept"), second.texts())
        second.quit()

        // Decided, it is still told after another restart, with what it changed
        val third = world.start()
        val decided = third.call("proposal_status", buildJsonObject { put("proposal", id) })
        assertEquals("decided", decided.getValue("state").jsonPrimitive.content)
        assertEquals(1, decided.getValue("changed").jsonArray.single().jsonArray.size)
        assertEquals(1, decided.getValue("placed").jsonArray.single().jsonArray.size)
    }

    @Test
    fun aProposalMadeOnWhatChangedMeanwhileIsStaleAfterARestart() = runTest {
        val world = world(this, backgroundScope)
        val first = world.start()
        val text = first.open.edit { session ->
            TextElement().apply { this.text = "old"; x = 10f; y = 20f; width = 100f; height = 10f }.also { session.submit(AddElements.of(session.document.pages.first(), listOf(it))) }
        }
        val base = first.call("read_page", buildJsonObject { put("document", world.documentId) }).getValue("texts").jsonArray.single().jsonObject.getValue("base").jsonPrimitive.content
        val id = first.propose(world.documentId, buildJsonObject {
            put("kind", "replace_text"); put("text", text.id); put("base", base); put("markdown", "from the model")
        })
        first.open.edit { session -> session.submit(UpdateTexts(session.document.pages.first().id, listOf(text.toData().copy(text = "written since")))) }
        first.quit()

        val second = world.start()
        second.agents.decide(id, setOf(0))
        assertEquals(ItemOutcome.STALE, second.agents.proposals.find(id)!!.entries.single().outcome)
        assertEquals(listOf("written since"), second.texts())
    }

    @Test
    fun aBatchIsUndoneAfterARestartUnlessWhatItChangedChangedSince() = runTest {
        val world = world(this, backgroundScope)
        val first = world.start()
        first.agents.access.setApplyDirectly(true)
        first.propose(world.documentId, insert("One"))
        first.propose(world.documentId, insert("Two"))
        first.quit()

        val second = world.start()
        val batches = second.call("list_activity").getValue("batches").jsonArray.map { it.jsonObject }
        assertEquals(2, batches.size)
        // The most recent first: "Two" is written on by the user, "One" is as its batch left it
        val two = second.open.document.pages.first().texts.first { it.text == "Two" }
        second.open.edit { session -> session.submit(UpdateTexts(session.document.pages.first().id, listOf(two.toData().copy(text = "Two, and more")))) }
        assertEquals(UndoOutcome.CHANGED, second.agents.undo(batches[0].getValue("id").jsonPrimitive.content))
        assertEquals(UndoOutcome.UNDONE, second.agents.undo(batches[1].getValue("id").jsonPrimitive.content))
        assertEquals(listOf("Two, and more"), second.texts())
        second.quit()

        val third = world.start()
        val kept = third.agents.activity.batches.value
        assertEquals(listOf(true, false), kept.map { it.undone })
        assertEquals(UndoOutcome.GONE, third.agents.undo(kept[0].id))
    }

    @Test
    fun aBatchOfTheLibraryIsUndoneAfterARestart() = runTest {
        val world = world(this, backgroundScope)
        val first = world.start()
        first.agents.access.setApplyDirectly(true)
        first.call("propose_library", buildJsonObject {
            put("title", "A folder")
            put("items", JsonArray(listOf(buildJsonObject { put("kind", "create_folder"); put("name", "Made by a model") })))
        })
        assertEquals(1, world.store.folders.first().size)
        first.quit()

        val second = world.start()
        val batch = second.agents.activity.batches.value.single()
        assertNull(batch.documentId)
        assertEquals(UndoOutcome.UNDONE, second.agents.undo(batch.id))
        assertTrue(world.store.folders.first().isEmpty())
    }

    @Test
    fun aJournalThatIsNotUnderstoodIsPutAsideAsItIs() = runTest {
        for (text in listOf("""{"version": 2, "proposals": [{"id": "from the future"}]}""", """{"version": 1, "proposals": [{"id": "cut""", "not a journal", """{"version": 1, "proposals": {}}""")) {
            val journal = FakeJournal(text)
            val world = world(this, backgroundScope, journal)
            val run = world.start()
            assertEquals(listOf(text), journal.aside)
            assertNull(journal.kept)
            assertTrue(run.agents.proposals.proposals.value.isEmpty())
            // What is done after is kept under the name of the journal, beside what was put aside
            run.propose(world.documentId, insert("New"))
            assertEquals(1, JournalCodec.decode(assertNotNull(journal.kept))!!.proposals.size)
            assertEquals(listOf(text), journal.aside)
        }
    }

    @Test
    fun whatIsNotUnderstoodInAJournalIsSkippedAndWrittenBackAsItWas() = runTest {
        val world = world(this, backgroundScope)
        val first = world.start()
        first.agents.access.setApplyDirectly(true)
        first.propose(world.documentId, insert("Applied"))
        first.quit()

        // As a later version would write it: an item, a proposal of the library and a change it does not know
        val written = Json.parseToJsonElement(assertNotNull(world.journal.kept)).jsonObject
        val unknownProposal = Json.parseToJsonElement("""{"id": "p", "document": "d", "client": "c", "title": "t", "entries": [{"item": {"kind": "fold_page", "page": "x"}, "outcome": "waiting"}]}""")
        val unknownLibrary = Json.parseToJsonElement("""{"id": "l", "client": "c", "title": "t", "items": [{"kind": "empty_trash"}], "outcomes": ["waiting"]}""")
        val unknownBatch = Json.parseToJsonElement("""{"id": "b", "client": "c", "title": "t", "items": "many"}""")
        val batch = written.getValue("activity").jsonArray.single().jsonObject
        val unknownUndo = Json.parseToJsonElement("""{"change": "{\"type\":\"foldPage\"}", "stamp": ${batch.getValue("undo").jsonObject.getValue("stamp")}}""")
        world.journal.kept = JsonObject(
            written + mapOf(
                "proposals" to JsonArray(written.getValue("proposals").jsonArray + unknownProposal),
                "library" to JsonArray(listOf(unknownLibrary)),
                "activity" to JsonArray(listOf(unknownBatch, JsonObject(batch + ("undo" to unknownUndo))))
            )
        ).toString()

        val second = world.start()
        assertEquals(1, second.agents.proposals.proposals.value.size)
        assertTrue(second.agents.library.proposals.value.isEmpty())
        val kept = second.agents.activity.batches.value.single()
        assertFalse(kept.undoable)
        assertEquals(UndoOutcome.GONE, second.agents.undo(kept.id))
        assertEquals(listOf("Applied"), second.texts())
        assertEquals("false", second.call("list_activity").getValue("batches").jsonArray.single().jsonObject.getValue("undoable").jsonPrimitive.content)

        // Written again, each of them is there as it was
        second.propose(world.documentId, insert("Another"))
        val again = Json.parseToJsonElement(assertNotNull(world.journal.kept)).jsonObject
        assertTrue(unknownProposal in again.getValue("proposals").jsonArray)
        assertEquals(listOf(unknownLibrary), again.getValue("library").jsonArray.toList())
        assertTrue(unknownBatch in again.getValue("activity").jsonArray)
        assertEquals(unknownUndo, again.getValue("activity").jsonArray.first { it.jsonObject["id"] == JsonPrimitive(kept.id) }.jsonObject.getValue("undo"))
    }

    @Test
    fun whatAJournalHoldsIsBoundedAndCheckedAgain() = runTest {
        val world = world(this, backgroundScope)
        val first = world.start()
        first.agents.access.setApplyDirectly(true)
        repeat(ProposalBook.DEFAULT_MAX_DECIDED + 5) { first.propose(world.documentId, insert("Text $it")) }
        first.quit()
        val second = world.start()
        assertEquals(ProposalBook.DEFAULT_MAX_DECIDED, second.agents.proposals.proposals.value.size)
        assertEquals(ProposalBook.DEFAULT_MAX_DECIDED + 5, second.agents.activity.batches.value.size)

        // An item no client could have proposed is not taken from a file either
        val blank = """{"version": 1, "proposals": [{"id": "p", "document": "${world.documentId}", "client": "c", "title": "t", "entries": [{"item": {"kind": "insert_text", "markdown": " "}, "outcome": "waiting"}]}]}"""
        val content = assertNotNull(JournalCodec.decode(blank))
        assertTrue(content.proposals.isEmpty())
        assertEquals(1, content.unread.proposals.size)
        assertNull(JournalCodec.decode("[".repeat(100) + "]".repeat(100)))
    }

    @Test
    fun nothingIsWrittenOverAJournalNotReadYet() = runTest {
        val journal = FakeJournal("""{"version": 1, "proposals": [], "library": [], "activity": []}""")
        val world = world(this, backgroundScope, journal)
        val documents = OpenDocuments(world.store, StandardTestDispatcher(testScheduler), backgroundScope)
        val agents = ExternalAgents(documents, AgentEngines(), world.store, journal)
        agents.access.setEnabled(true)
        agents.access.setAllDocuments(true)
        // The first tool called reads it back by itself
        agents.tools.call("list_activity", JsonObject(emptyMap()), agents.access.connect("test").id)
        assertEquals(0, journal.saves)
        assertTrue(journal.aside.isEmpty())
    }

    /**
     * The journal as version 1 writes it, recorded: a change of this text is a change of a stored
     * format, which needs a new version and what reads the old one.
     */
    @Test
    fun theJournalOfVersionOneIsReadAsItWasWritten() = runTest {
        val content = assertNotNull(JournalCodec.decode(RECORDED))
        val proposal = content.proposals.single()
        assertEquals("A change", proposal.title)
        assertEquals(ItemOutcome.ACCEPTED, proposal.entries.single().outcome)
        assertIs<ProposalItem.InsertText>(proposal.entries.single().item)
        assertEquals(1, proposal.entries.single().placed.size)
        assertEquals(1, proposal.entries.single().changed.size)
        assertEquals(ItemOutcome.ACCEPTED, content.library.single().outcomes.single())
        assertEquals(2, content.batches.size)
        assertEquals(2, content.undos.size)
        assertIs<DocumentUndo>(content.undos.getValue(content.batches[0].id))
        assertIs<LibraryUndos>(content.undos.getValue(content.batches[1].id))
        assertTrue(content.unread.proposals.isEmpty() && content.unread.library.isEmpty() && content.unread.activity.isEmpty() && content.unread.undos.isEmpty())
        // And written again as it was
        assertEquals(
            Json.parseToJsonElement(RECORDED),
            Json.parseToJsonElement(JournalCodec.encode(content.proposals, content.library, content.batches, { content.undos[it] }, content.unread))
        )
    }

    private companion object {
        const val RECORDED = """{"version":1,"proposals":[{"id":"3d4950c8-4d4d-42a2-bbf2-a1e6a127f008","document":"19d282f4-acf8-4a3b-9b03-e917702cadb1","client":"test","title":"A change","entries":[{"item":{"kind":"insert_text","markdown":"Kept","new_page":false},"outcome":"accepted","placed":[{"id":"77784746-5217-49a2-b2ad-9f855a8c394b","markdown":"Kept","page":"ce3ec29f-7094-4150-8415-0d4bd0e2f4ff","new_page":false,"left":10.0,"top":10.0,"right":200.0,"bottom":16.77184,"font_size":16.0,"color":-14722394}],"changed":[{"id":"77784746-5217-49a2-b2ad-9f855a8c394b","base":"01a1cccd2e38.00000000.fab01a8a-8f3a-4766-9f74-acec41cd5005#d5e577de736671b9"}]}]}],"library":[{"id":"e013a9c2-6414-487e-9aea-4b49b457f7b7","client":"test","title":"A folder","items":[{"kind":"create_folder","name":"Made by a model"}],"outcomes":["accepted"],"created":[]}],"activity":[{"id":"f6604b3a-1bf5-49df-9d94-d6a7641ebd75","document":"19d282f4-acf8-4a3b-9b03-e917702cadb1","client":"test","title":"A change","items":1,"at":1794437361208,"undone":false,"undo":{"change":"{\"type\":\"composite\",\"changes\":[{\"type\":\"removeElements\",\"pageId\":\"ce3ec29f-7094-4150-8415-0d4bd0e2f4ff\",\"ids\":[\"77784746-5217-49a2-b2ad-9f855a8c394b\"]}]}","stamp":"01a1cccd2e38.00000000.fab01a8a-8f3a-4766-9f74-acec41cd5005"}},{"id":"4c6c6269-bfc9-4dac-b051-669ca7bbf35a","client":"test","title":"A folder","items":1,"at":1794437361211,"undone":false,"undo":{"steps":[{"kind":"created_folder","folder":"ef18d201-e36f-48f9-9f32-3078018bc39e","name":"Made by a model"}]}}]}"""
    }
}
