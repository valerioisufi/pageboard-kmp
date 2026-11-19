package io.github.valerioisufi.drawview.agent

import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.change.ChangeObserver
import io.github.valerioisufi.drawview.model.change.ChangeOrigin
import io.github.valerioisufi.drawview.model.change.CommittedChange
import io.github.valerioisufi.drawview.model.change.DocumentSession
import io.github.valerioisufi.drawview.model.change.Operation
import kotlinx.serialization.json.Json
import kotlin.test.assertTrue
import io.github.valerioisufi.drawview.store.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.store.KeyValueDocumentStore
import io.github.valerioisufi.drawview.store.OpenDocument
import io.github.valerioisufi.drawview.store.OpenDocuments
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * A document of one page reached by a client of the tools, the access turned on: what the tests of
 * `docs/plans/agent-coverage.md` start from.
 */
internal class AgentHarness private constructor(
    val store: KeyValueDocumentStore,
    val documents: OpenDocuments,
    val agents: ExternalAgents,
    val connection: String,
    val documentId: String,
    val open: OpenDocument
) {
    val page: DrawPage get() = open.document.pages[0]

    suspend fun call(name: String, arguments: JsonObject = JsonObject(emptyMap())): JsonObject =
        assertIs<ToolResult.Value>(agents.tools.call(name, arguments, connection)).json.jsonObject

    suspend fun propose(vararg items: JsonObject, document: String = documentId): JsonObject = call(
        "propose",
        buildJsonObject {
            put("document", document)
            put("title", "A change")
            put("items", JsonArray(items.toList()))
        }
    )

    suspend fun proposeLibrary(vararg items: JsonObject): JsonObject = call(
        "propose_library",
        buildJsonObject {
            put("title", "Tidy up")
            put("items", JsonArray(items.toList()))
        }
    )

    suspend fun refused(vararg items: JsonObject): AgentToolException = assertFailsWith<AgentToolException> { propose(*items) }

    fun outcomes(answer: JsonObject): List<String> = answer.getValue("items").jsonArray.map { it.jsonPrimitive.content }

    /** The page read as read_page gives it. */
    suspend fun readPage(page: String = this.page.id, document: String = documentId): JsonObject =
        call("read_page", buildJsonObject { put("document", document); put("page", page); put("strokes", true) })

    /** The bases read_page gives, by id of an element of any kind. */
    suspend fun bases(page: String = this.page.id, document: String = documentId): Map<String, String> {
        val read = readPage(page, document)
        return listOf("texts", "images", "pdfs", "custom_elements", "markups", "stroke_list").flatMap { key ->
            read[key]?.jsonArray.orEmpty().map { it.jsonObject }
        }.associate { it.getValue("id").jsonPrimitive.content to it.getValue("base").jsonPrimitive.content }
    }

    /** [ids] with the bases read_page gives them. */
    suspend fun refs(vararg ids: String, page: String = this.page.id, document: String = documentId): JsonArray {
        val bases = bases(page, document)
        return buildJsonArray {
            for (id in ids) add(buildJsonObject { put("id", id); put("base", bases.getValue(id)) })
        }
    }

    /** The batches of the activity undone, the last first. */
    suspend fun undoLast(): UndoOutcome = agents.undo(agents.activity.batches.value.last().id)

    /**
     * Another device with the document as it is now, which receives the operations [block] makes as
     * they are written: what travels.
     */
    suspend fun converging(block: suspend () -> Unit): DocumentSession {
        open.flush()
        val other = DocumentSession(assertNotNull(store.loadDocument(documentId)), "other", store.versionVector(documentId))
        val operations = mutableListOf<CommittedChange>()
        open.session.addObserver(ChangeObserver { if (it.origin == ChangeOrigin.LOCAL) operations += it })
        block()
        for (operation in operations) {
            val sent = Json.decodeFromString(Operation.serializer(), Json.encodeToString(Operation.serializer(), operation.operation))
            assertTrue(other.receive(sent))
        }
        return other
    }

    companion object {
        /**
         * A document of one A4 page, allowed ([all]: every document), its proposals applied at once
         * unless [direct] is false.
         */
        suspend fun of(
            test: TestScope,
            scope: CoroutineScope,
            engines: AgentEngines = AgentEngines(),
            all: Boolean = false,
            direct: Boolean = true,
            background: PageBackground = PageBackground.Solid()
        ): AgentHarness {
            val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = scope)
            val documents = OpenDocuments(store, StandardTestDispatcher(test.testScheduler), scope)
            val agents = ExternalAgents(documents, engines, store)
            val document = store.createDocument("Physics", 210f, 297f, background)
            val open = assertNotNull(documents.open(document.id))
            agents.access.setEnabled(true)
            if (all) agents.access.setAllDocuments(true) else agents.access.grant(document.id)
            agents.access.setApplyDirectly(direct)
            return AgentHarness(store, documents, agents, agents.access.connect("test").id, document.id, open)
        }

        inline fun item(kind: String, build: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
            put("kind", kind)
            build()
        }
    }
}
