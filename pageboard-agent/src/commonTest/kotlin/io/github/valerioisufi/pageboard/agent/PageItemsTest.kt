package io.github.valerioisufi.pageboard.agent

import io.github.valerioisufi.pageboard.model.DrawText
import io.github.valerioisufi.pageboard.model.PageBackground
import io.github.valerioisufi.pageboard.model.change.AddElements
import io.github.valerioisufi.pageboard.model.change.ChangeObserver
import io.github.valerioisufi.pageboard.model.change.ChangeOrigin
import io.github.valerioisufi.pageboard.model.change.CommittedChange
import io.github.valerioisufi.pageboard.model.change.DocumentSession
import io.github.valerioisufi.pageboard.model.change.Operation
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The items of a proposal on the pages and on the document. */
class PageItemsTest {

    private class Setup(val store: KeyValueDocumentStore, val agents: ExternalAgents, val connection: String, val documentId: String, val open: OpenDocument)

    private suspend fun setup(test: TestScope, scope: CoroutineScope, pages: Int = 3): Setup {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = scope)
        val documents = OpenDocuments(store, StandardTestDispatcher(test.testScheduler), scope)
        val agents = ExternalAgents(documents, AgentEngines(), store)
        val document = store.createDocument("Physics", 210f, 297f, PageBackground.Solid())
        val open = assertNotNull(documents.open(document.id))
        agents.access.setEnabled(true)
        agents.access.grant(document.id)
        agents.access.setApplyDirectly(true)
        val setup = Setup(store, agents, agents.access.connect("test").id, document.id, open)
        repeat(pages - 1) { setup.propose(item("add_page") {}) }
        return setup
    }

    private suspend fun Setup.call(name: String, arguments: JsonObject): JsonObject =
        assertIs<ToolResult.Value>(agents.tools.call(name, arguments, connection)).json.jsonObject

    private suspend fun Setup.propose(vararg items: JsonObject): List<String> = call(
        "propose",
        buildJsonObject {
            put("document", documentId)
            put("title", "A change")
            put("items", JsonArray(items.toList()))
        }
    ).getValue("items").jsonArray.map { it.jsonPrimitive.content }

    private suspend fun Setup.refused(vararg items: JsonObject): AgentToolException = assertFailsWith<AgentToolException> { propose(*items) }

    private val Setup.pageIds: List<String> get() = open.document.pages.map { it.id }

    private suspend fun Setup.pageBase(index: Int): String =
        call("read_page", buildJsonObject { put("document", documentId); put("index", index) }).getValue("base").jsonPrimitive.content

    private inline fun item(kind: String, build: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("kind", kind)
        build()
    }

    private fun countOperations(open: OpenDocument): MutableList<CommittedChange> {
        val operations = mutableListOf<CommittedChange>()
        open.session.addObserver(ChangeObserver { if (it.origin == ChangeOrigin.LOCAL) operations += it })
        return operations
    }

    @Test
    fun aPageRemovedGoesToTheTrashOfThePages() = runTest {
        val setup = setup(this, backgroundScope)
        val (first, second, third) = setup.pageIds
        assertEquals(listOf("accepted"), setup.propose(item("remove_page") { put("page", second); put("base", setup.pageBase(1)) }))
        assertEquals(listOf(first, third), setup.pageIds)
        assertEquals(listOf(second), setup.open.document.removedPagesSince(0L).map { it.id })
    }

    @Test
    fun aPageWrittenOnAfterItWasReadIsNotRemoved() = runTest {
        val setup = setup(this, backgroundScope)
        setup.agents.access.setApplyDirectly(false)
        val page = setup.pageIds[1]
        val base = setup.pageBase(1)
        setup.open.edit { session ->
            val text = DrawText().apply { text = "Written after"; x = 10f; y = 20f; width = 100f; height = 10f; fontSize = 16f }
            session.submit(AddElements.of(session.document.pageById(page)!!, listOf(text)))
        }
        val id = setup.call(
            "propose",
            buildJsonObject {
                put("document", setup.documentId)
                put("title", "Remove")
                put("items", JsonArray(listOf(item("remove_page") { put("page", page); put("base", base) })))
            }
        ).getValue("proposal").jsonPrimitive.content
        setup.agents.decide(id, setOf(0))
        assertEquals(ItemOutcome.STALE, setup.agents.proposals.find(id)!!.entries.single().outcome)
        assertTrue(page in setup.pageIds)
    }

    @Test
    fun aDocumentKeepsOnePage() = runTest {
        val setup = setup(this, backgroundScope, pages = 2)
        val (first, second) = setup.pageIds
        val outcomes = setup.propose(
            item("remove_page") { put("page", first); put("base", setup.pageBase(0)) },
            item("remove_page") { put("page", second); put("base", setup.pageBase(1)) }
        )
        assertEquals(listOf("accepted", "stale"), outcomes)
        assertEquals(listOf(second), setup.pageIds)
    }

    @Test
    fun aPageIsMovedAfterAnotherOrFirst() = runTest {
        val setup = setup(this, backgroundScope)
        val (first, second, third) = setup.pageIds
        setup.propose(item("move_page") { put("page", first); put("after", third) })
        assertEquals(listOf(second, third, first), setup.pageIds)
        setup.propose(item("move_page") { put("page", first) })
        assertEquals(listOf(first, second, third), setup.pageIds)
        assertEquals(AgentErrorCode.REFUSED, setup.refused(item("move_page") { put("page", first); put("after", first) }).code)
    }

    @Test
    fun aPageIsDuplicatedWithWhatItHolds() = runTest {
        val setup = setup(this, backgroundScope)
        val (first, second, third) = setup.pageIds
        setup.open.edit { session ->
            val text = DrawText().apply { text = "Gauss"; x = 10f; y = 20f; width = 100f; height = 10f; fontSize = 16f }
            session.submit(AddElements.of(session.document.pageById(first)!!, listOf(text)))
        }
        setup.propose(item("duplicate_page") { put("page", first) })
        val pages = setup.open.document.pages
        assertEquals(4, pages.size)
        assertEquals(listOf(first), listOf(pages[0].id))
        assertEquals(listOf(second, third), listOf(pages[2].id, pages[3].id))
        assertEquals(listOf("Gauss"), pages[1].texts.map { it.text })
        assertTrue(pages[1].texts.single().id != pages[0].texts.single().id)
    }

    @Test
    fun theFormatOfAPageIsChanged() = runTest {
        val setup = setup(this, backgroundScope)
        val page = setup.pageIds[0]
        setup.propose(item("set_page_format") { put("page", page); put("base", setup.pageBase(0)); put("width_mm", 297f); put("background", "grid"); put("spacing_mm", 7f) })
        val formatted = setup.open.document.pageById(page)!!
        assertEquals(297f, formatted.widthMm)
        assertEquals(297f, formatted.heightMm)
        assertEquals(PageBackground.Grid(spacingMm = 7f), formatted.background)
        assertEquals("grid", setup.call("read_page", buildJsonObject { put("document", setup.documentId) }).getValue("background").jsonPrimitive.content)

        setup.propose(item("set_page_format") { put("page", page); put("base", setup.pageBase(0)); put("background", "document") })
        assertTrue(!setup.open.document.pageById(page)!!.hasCustomBackground)

        assertEquals(AgentErrorCode.REFUSED, setup.refused(item("set_page_format") { put("page", page); put("base", setup.pageBase(0)); put("width_mm", 5f) }).code)
        assertEquals(AgentErrorCode.REFUSED, setup.refused(item("set_page_format") { put("page", page); put("base", setup.pageBase(0)); put("background", "stripes") }).code)
        assertEquals(AgentErrorCode.REFUSED, setup.refused(item("set_page_format") { put("page", page); put("base", setup.pageBase(0)) }).code)
    }

    @Test
    fun theDocumentIsRenamedByAFreeName() = runTest {
        val setup = setup(this, backgroundScope, pages = 1)
        setup.store.createDocument("Chemistry", 210f, 297f, PageBackground.Solid())
        assertEquals(AgentErrorCode.REFUSED, setup.refused(item("rename_document") { put("title", "Chemistry") }).code)
        setup.propose(item("rename_document") { put("title", "Physics I") })
        assertEquals("Physics I", setup.open.document.title)
        setup.open.flush()
        assertEquals("Physics I", setup.store.documents.first().first { it.id == setup.documentId }.name)
    }

    @Test
    fun aBatchOfPagesIsUndoneAtOnceAndConverges() = runTest {
        val setup = setup(this, backgroundScope)
        val (first, second, third) = setup.pageIds
        setup.open.flush()
        val other = DocumentSession(assertNotNull(setup.store.loadDocument(setup.documentId)), "other", setup.store.versionVector(setup.documentId))
        val operations = countOperations(setup.open)
        setup.propose(
            item("remove_page") { put("page", second); put("base", setup.pageBase(1)) },
            item("move_page") { put("page", first); put("after", third) },
            item("duplicate_page") { put("page", third) },
            item("set_page_format") { put("page", third); put("base", setup.pageBase(2)); put("height_mm", 210f) },
            item("rename_document") { put("title", "Physics I") }
        )
        assertEquals(1, operations.size)
        val sent = Json.decodeFromString(Operation.serializer(), Json.encodeToString(Operation.serializer(), operations.single().operation))
        assertTrue(other.receive(sent))
        assertEquals(setup.pageIds, other.document.pages.map { it.id })
        assertEquals(setup.open.document.pages.map { it.heightMm }, other.document.pages.map { it.heightMm })
        assertEquals("Physics I", other.document.title)

        val batch = setup.agents.activity.batches.value.last()
        assertEquals(UndoOutcome.UNDONE, setup.agents.undo(batch.id))
        assertEquals(listOf(first, second, third), setup.pageIds)
        assertEquals(297f, setup.open.document.pageById(third)!!.heightMm)
        assertEquals("Physics", setup.open.document.title)
    }

    @Test
    fun aPageIsChangedByOneItemOnly() = runTest {
        val setup = setup(this, backgroundScope)
        val page = setup.pageIds[0]
        val refused = setup.refused(
            item("move_page") { put("page", page); put("after", setup.pageIds[2]) },
            item("remove_page") { put("page", page); put("base", setup.pageBase(0)) }
        )
        assertEquals(AgentErrorCode.REFUSED, refused.code)
    }
}
