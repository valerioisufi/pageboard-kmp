package io.github.valerioisufi.drawview.agent

import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.store.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.store.KeyValueDocumentStore
import io.github.valerioisufi.drawview.store.OpenDocuments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AgentEndpointTest {
    private val meta = """"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientInfo":{"name":"Tester","version":"1"}}"""

    private class Setup(val agents: ExternalAgents, val connection: AgentEndpointConnection, val documentId: String)

    private suspend fun setup(test: TestScope, scope: CoroutineScope): Setup {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = scope)
        val documents = OpenDocuments(store, StandardTestDispatcher(test.testScheduler), scope)
        val agents = ExternalAgents(documents, AgentEngines(), store)
        val document = store.createDocument("Physics", 210f, 297f, PageBackground.Solid())
        agents.access.setEnabled(true)
        agents.access.grant(document.id)
        return Setup(agents, AgentEndpoint(agents, "PencilNotes", "1.0").connect(), document.id)
    }

    private suspend fun Setup.answer(message: String): JsonObject =
        Json.parseToJsonElement(assertNotNull(connection.handle(message))).jsonObject

    private fun JsonObject.errorCode(): Int = getValue("error").jsonObject.getValue("code").jsonPrimitive.int

    private fun JsonObject.result(): JsonObject = getValue("result").jsonObject

    @Test
    fun aModernClientIsAnsweredWithoutAHandshake() = runTest {
        val setup = setup(this, backgroundScope)
        val discovered = setup.answer("""{"jsonrpc":"2.0","id":1,"method":"server/discover","params":{$meta}}""").result()
        assertEquals("complete", discovered.getValue("resultType").jsonPrimitive.content)
        assertTrue(discovered.getValue("supportedVersions").jsonArray.any { it.jsonPrimitive.content == "2026-07-28" })

        val list = setup.answer("""{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{$meta}}""").result()
        assertEquals("complete", list.getValue("resultType").jsonPrimitive.content)
        assertEquals("private", list.getValue("cacheScope").jsonPrimitive.content)
        assertTrue(list.getValue("tools").jsonArray.any { it.jsonObject.getValue("name").jsonPrimitive.content == "propose" })

        val call = setup.answer("""{"jsonrpc":"2.0","id":"three","method":"tools/call","params":{$meta,"name":"list_documents"}}""")
        assertEquals("three", call.getValue("id").jsonPrimitive.content)
        assertFalse(call.result().getValue("isError").jsonPrimitive.boolean)
        val listed = call.result().getValue("structuredContent").jsonObject.getValue("documents").jsonArray
        assertEquals(setup.documentId, listed.single().jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("Tester", setup.agents.access.connections.value.single().name)
    }

    @Test
    fun aVersionNotServedIsToldWhichOnesAre() = runTest {
        val setup = setup(this, backgroundScope)
        val answer = setup.answer("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"1900-01-01"}}}""")
        assertEquals(-32022, answer.errorCode())
        val data = answer.getValue("error").jsonObject.getValue("data").jsonObject
        assertEquals("1900-01-01", data.getValue("requested").jsonPrimitive.content)
        assertTrue(data.getValue("supported").jsonArray.isNotEmpty())
    }

    @Test
    fun aLegacyClientOpensWithInitialize() = runTest {
        val setup = setup(this, backgroundScope)
        val opened = setup.answer("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"Old","version":"1"}}}""").result()
        assertEquals("2025-06-18", opened.getValue("protocolVersion").jsonPrimitive.content)
        assertTrue("tools" in opened.getValue("capabilities").jsonObject)
        assertEquals("Old", setup.agents.access.connections.value.single().name)

        assertNull(setup.connection.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        assertTrue(setup.answer("""{"jsonrpc":"2.0","id":2,"method":"ping"}""").result().isEmpty())

        val list = setup.answer("""{"jsonrpc":"2.0","id":3,"method":"tools/list"}""").result()
        assertFalse("resultType" in list)
        assertTrue(list.getValue("tools").jsonArray.isNotEmpty())
    }

    @Test
    fun aLegacyClientAskingAnUnknownVersionGetsTheLatestOldOne() = runTest {
        val setup = setup(this, backgroundScope)
        val opened = setup.answer("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2000-01-01"}}""").result()
        assertEquals("2025-11-25", opened.getValue("protocolVersion").jsonPrimitive.content)
    }

    @Test
    fun aRequestWithNoVersionAndNoHandshakeIsRefused() = runTest {
        val setup = setup(this, backgroundScope)
        assertEquals(-32600, setup.answer("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""").errorCode())
        assertEquals(-32601, setup.answer("""{"jsonrpc":"2.0","id":2,"method":"ping"}""").errorCode())
    }

    @Test
    fun whatIsNotUnderstoodAnswersAnError() = runTest {
        val setup = setup(this, backgroundScope)
        assertEquals(-32700, setup.answer("not json").errorCode())
        assertEquals(-32600, setup.answer("[1,2]").errorCode())
        assertEquals(-32600, setup.answer("""{"id":1}""").errorCode())
        assertEquals(-32600, setup.answer("""{"id":{},"method":"tools/list"}""").errorCode())
        assertEquals(-32601, setup.answer("""{"id":1,"method":"nothing"}""").errorCode())
        assertEquals(-32602, setup.answer("""{"id":1,"method":"tools/call","params":{$meta}}""").errorCode())
        assertEquals(-32602, setup.answer("""{"id":1,"method":"tools/call","params":{$meta,"name":"list_documents","arguments":3}}""").errorCode())
        // A notification answers nothing
        assertNull(setup.connection.handle("""{"method":"anything"}"""))
    }

    @Test
    fun whatIsTooDeepOrTooLongIsNotDecoded() = runTest {
        val setup = setup(this, backgroundScope)
        val deep = "[".repeat(100_000) + "]".repeat(100_000)
        assertEquals(-32600, setup.answer(deep).errorCode())
        val objects = """{"a":""".repeat(100_000)
        assertEquals(-32600, setup.answer(objects).errorCode())
        val long = "x".repeat(AgentEndpoint.MAX_MESSAGE_LENGTH + 1)
        assertEquals(-32600, setup.answer(long).errorCode())
    }

    @Test
    fun bracketsInAStringDoNotCountAsNesting() {
        assertTrue(jsonNestsWithin("""{"a":"[[[[[[[[[[\"[[["}""", 1))
        assertFalse(jsonNestsWithin("""[[1]]""", 1))
    }

    @Test
    fun aToolRefusingIsAnErrorForTheModelWithItsCode() = runTest {
        val setup = setup(this, backgroundScope)
        val call = setup.answer("""{"id":1,"method":"tools/call","params":{$meta,"name":"read_document","arguments":{"document":"elsewhere"}}}""").result()
        assertTrue(call.getValue("isError").jsonPrimitive.boolean)
        assertEquals("not_granted", call.getValue("structuredContent").jsonObject.getValue("code").jsonPrimitive.content)
    }

    @Test
    fun theAccessTurnedOffIsToldToTheClient() = runTest {
        val setup = setup(this, backgroundScope)
        setup.agents.access.setEnabled(false)
        val call = setup.answer("""{"id":1,"method":"tools/call","params":{$meta,"name":"list_documents"}}""").result()
        assertEquals("access_off", call.getValue("structuredContent").jsonObject.getValue("code").jsonPrimitive.content)
    }

    @Test
    fun aClientGoneIsNoLongerConnected() = runTest {
        val setup = setup(this, backgroundScope)
        assertEquals(1, setup.agents.access.connections.value.size)
        setup.connection.close()
        assertEquals(0, setup.agents.access.connections.value.size)
    }
}
