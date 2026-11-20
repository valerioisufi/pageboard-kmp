package io.github.valerioisufi.pageboard.agent

import io.github.valerioisufi.pageboard.model.PageBackground
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Notes exported into files and pages shown in the editor (`docs/plans/agent-coverage.md`, phase 6). */
class CoverageExportTest {

    private class FakeExports : AgentExports {
        val asked = mutableListOf<AgentExportRequest>()
        override suspend fun export(request: AgentExportRequest): AgentExported {
            asked += request
            return AgentExported("/exports/agent/Physics.${request.format}", 1234L, request.documentIds.size.coerceAtLeast(1), 1)
        }
    }

    private suspend fun AgentHarness.export(vararg arguments: Pair<String, Any>) = call("export", buildJsonObject {
        for ((key, value) in arguments) when (value) {
            is String -> put(key, value)
            is List<*> -> put(key, JsonArray(value.map { JsonPrimitive(it as String) }))
        }
    })

    @Test
    fun aDocumentIsExportedIntoAFileOfTheApplication() = runTest {
        val exports = FakeExports()
        val harness = AgentHarness.of(this, backgroundScope, AgentEngines(exports = exports))
        val answer = harness.export("format" to "pdf", "document" to harness.documentId, "pages" to listOf(harness.page.id))
        assertEquals("/exports/agent/Physics.pdf", answer.getValue("path").jsonPrimitive.content)
        assertEquals(1234L, answer.getValue("bytes").jsonPrimitive.long)
        val request = exports.asked.single()
        assertEquals(listOf(harness.documentId), request.documentIds)
        assertEquals(setOf(harness.page.id), request.pageIds)
        assertNull(request.folderId)
    }

    @Test
    fun theFormatsFollowWhatIsExportedAndTheAccess() = runTest {
        val exports = FakeExports()
        val harness = AgentHarness.of(this, backgroundScope, AgentEngines(exports = exports))
        val other = harness.store.createDocument("Chemistry", 210f, 297f, PageBackground.Solid())
        suspend fun refused(vararg arguments: Pair<String, Any>) = assertFailsWith<AgentToolException> { harness.export(*arguments) }.code

        assertEquals(AgentErrorCode.REFUSED, refused("format" to "combined_pdf", "document" to harness.documentId))
        assertEquals(AgentErrorCode.NOT_GRANTED, refused("format" to "combined_pdf", "documents" to listOf(harness.documentId, other.id)))
        harness.agents.access.grant(other.id)
        harness.export("format" to "combined_pdf", "documents" to listOf(harness.documentId, other.id))
        assertEquals(AgentErrorCode.REFUSED, refused("format" to "markdown", "documents" to listOf(harness.documentId, other.id)))
        assertEquals(AgentErrorCode.NOT_FOUND, refused("format" to "pdf", "document" to harness.documentId, "pages" to listOf("elsewhere")))
        assertEquals(AgentErrorCode.BAD_REQUEST, refused("format" to "pdf"))
        assertEquals(AgentErrorCode.BAD_REQUEST, refused("format" to "pdf", "document" to harness.documentId, "folder" to "any"))

        harness.store.createFolder("Courses", null)
        val folder = harness.store.folders.first().single().id
        assertEquals(AgentErrorCode.NOT_GRANTED, refused("format" to "pageboard", "folder" to folder))
        harness.agents.access.setAllDocuments(true)
        harness.export("format" to "pageboard", "folder" to folder)
        assertEquals(folder, exports.asked.last().folderId)
        assertEquals(2, exports.asked.size)
    }

    @Test
    fun theToolsAreOfferedOnlyWithTheirEngines() = runTest {
        val without = AgentHarness.of(this, backgroundScope)
        assertTrue(without.agents.tools.tools.none { it.name == "export" || it.name == "show_page" })
    }

    @Test
    fun aPageIsShownInTheEditor() = runTest {
        val shown = mutableListOf<Pair<String, String?>>()
        val harness = AgentHarness.of(this, backgroundScope, AgentEngines(show = { document: String, page: String? -> shown.add(document to page) }))
        harness.call("show_page", buildJsonObject { put("document", harness.documentId); put("page", harness.page.id) })
        assertEquals<List<Pair<String, String?>>>(listOf(harness.documentId to harness.page.id), shown)
        val missing = assertFailsWith<AgentToolException> { harness.call("show_page", buildJsonObject { put("document", harness.documentId); put("page", "elsewhere") }) }
        assertEquals(AgentErrorCode.NOT_FOUND, missing.code)
        val other = harness.store.createDocument("Chemistry", 210f, 297f, PageBackground.Solid())
        val hidden = assertFailsWith<AgentToolException> { harness.call("show_page", buildJsonObject { put("document", other.id) }) }
        assertEquals(AgentErrorCode.NOT_GRANTED, hidden.code)
        assertEquals(1, shown.size)
    }
}
