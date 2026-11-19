package io.github.valerioisufi.pageboard.agent

import io.github.valerioisufi.pageboard.model.PageBackground
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The handwriting transcribed, the lectures and the search in them, read by a model (`docs/plans/agent-coverage.md`, phase 7). */
class CoverageLecturesTest {

    private class FakeLectures : AgentLectures {
        val lecture = AgentLecture("lecture-1", 1_000L, 3_600_000L, hasReview = true, suggestions = 1, isRecording = false)
        val content = AgentLectureContent(
            lecture, "1. Gauss\n2. Faraday", "Faraday",
            (0 until 10).map { AgentSpoken(it * 60_000L, (it + 1) * 60_000L, "Minute $it. " + "x".repeat(40)) },
            "The law of Gauss, then Faraday.",
            listOf(AgentSuggestion("definition", "Flux", "The flux of a field", "page-1", 120_000L))
        )
        var reads = 0
        override suspend fun lectures(documentId: String): List<AgentLecture> = listOf(lecture)
        override suspend fun lecture(documentId: String, lectureId: String): AgentLectureContent? = content.takeIf { lectureId == lecture.id }.also { reads++ }
        override suspend fun course(documentId: String): String? = "Physics 2, second year"
    }

    private suspend fun AgentHarness.read(vararg arguments: Pair<String, Any>): JsonObject = call("read_lecture", buildJsonObject {
        put("document", documentId)
        for ((key, value) in arguments) when (value) {
            is String -> put(key, value)
            is Int -> put(key, value)
            is Long -> put(key, value)
            is List<*> -> put(key, JsonArray(value.map { JsonPrimitive(it as String) }))
        }
    })

    @Test
    fun theLecturesOfADocumentAreListedWithItsCourse() = runTest {
        val harness = AgentHarness.of(this, backgroundScope, AgentEngines(lectures = FakeLectures()))
        val answer = harness.call("list_lectures", buildJsonObject { put("document", harness.documentId) })
        val listed = answer.getValue("lectures").jsonArray.single().jsonObject
        assertEquals("lecture-1", listed.getValue("id").jsonPrimitive.content)
        assertEquals(3_600_000L, listed.getValue("duration_ms").jsonPrimitive.long)
        assertEquals("Physics 2, second year", answer.getValue("course").jsonPrimitive.content)
        assertTrue("notice" in answer)
    }

    @Test
    fun aLectureIsReadByPartsAndItsTranscriptByPieces() = runTest {
        val harness = AgentHarness.of(this, backgroundScope, AgentEngines(lectures = FakeLectures()))
        val all = harness.read("lecture" to "lecture-1")
        assertEquals("Faraday", all.getValue("current_topic").jsonPrimitive.content)
        assertEquals("The law of Gauss, then Faraday.", all.getValue("review").jsonPrimitive.content)
        assertEquals("page-1", all.getValue("suggestions").jsonArray.single().jsonObject.getValue("page").jsonPrimitive.content)
        assertEquals(10, all.getValue("transcript").jsonArray.size)
        assertTrue("next_ms" !in all)

        val some = harness.read("lecture" to "lecture-1", "parts" to listOf("transcript"), "from_ms" to 120_000L, "max_chars" to 120)
        assertTrue("outline" !in some && "review" !in some && "suggestions" !in some)
        val pieces = some.getValue("transcript").jsonArray.map { it.jsonObject }
        assertEquals(listOf(120_000L, 180_000L), pieces.map { it.getValue("start_ms").jsonPrimitive.long })
        assertEquals(240_000L, some.getValue("next_ms").jsonPrimitive.long)

        assertEquals(AgentErrorCode.NOT_FOUND, assertFailsWith<AgentToolException> { harness.read("lecture" to "elsewhere") }.code)
        assertEquals(AgentErrorCode.BAD_REQUEST, assertFailsWith<AgentToolException> { harness.read("lecture" to "lecture-1", "parts" to listOf("audio")) }.code)
    }

    @Test
    fun theLecturesOfADocumentNotAllowedAreNotRead() = runTest {
        val lectures = FakeLectures()
        val harness = AgentHarness.of(this, backgroundScope, AgentEngines(lectures = lectures))
        val other = harness.store.createDocument("Chemistry", 210f, 297f, PageBackground.Solid())
        val refused = assertFailsWith<AgentToolException> { harness.call("read_lecture", buildJsonObject { put("document", other.id); put("lecture", "lecture-1") }) }
        assertEquals(AgentErrorCode.NOT_GRANTED, refused.code)
        assertEquals(0, lectures.reads)
        assertTrue(AgentHarness.of(this, backgroundScope).agents.tools.tools.none { it.name == "list_lectures" })
    }

    @Test
    fun theHandwritingTranscribedIsInThePage() = runTest {
        val harness = AgentHarness.of(this, backgroundScope, AgentEngines(handwriting = { _, page -> "Gauss: flux = q / ε0".takeIf { page.isNotEmpty() } }))
        assertEquals("Gauss: flux = q / ε0", harness.readPage().getValue("handwriting").jsonPrimitive.content)
        val none = AgentHarness.of(this, backgroundScope)
        assertNull(none.readPage()["handwriting"])
    }

    @Test
    fun theSearchIsLimitedToTheDocumentsOrTheFolderAskedAndTellsTheLecture() = runTest {
        val asked = mutableListOf<Set<String>>()
        lateinit var harness: AgentHarness
        val engines = AgentEngines(search = { _, ids, _ ->
            asked += ids
            ids.map { AgentSearchHit(it, "A note", null, "flux", lectureId = "lecture-1", startMs = 120_000L) }
        })
        harness = AgentHarness.of(this, backgroundScope, engines, all = true)
        harness.store.createFolder("Courses", null)
        val folder = harness.store.folders.first().single().id
        val inside = harness.store.createDocument("Chemistry", 210f, 297f, PageBackground.Solid(), folder)

        val all = harness.call("search", buildJsonObject { put("query", "flux") }).getValue("passages").jsonArray
        assertEquals(2, all.size)
        val passage = all.first().jsonObject
        assertEquals("lecture-1", passage.getValue("lecture").jsonPrimitive.content)
        assertEquals(120_000L, passage.getValue("start_ms").jsonPrimitive.long)

        harness.call("search", buildJsonObject { put("query", "flux"); put("documents", JsonArray(listOf(JsonPrimitive(harness.documentId)))) })
        assertEquals(setOf(harness.documentId), asked.last())
        harness.call("search", buildJsonObject { put("query", "flux"); put("folder", folder) })
        assertEquals(setOf(inside.id), asked.last())

        harness.agents.access.setAllDocuments(false)
        harness.agents.access.grant(harness.documentId)
        val refused = assertFailsWith<AgentToolException> { harness.call("search", buildJsonObject { put("query", "flux"); put("folder", folder) }) }
        assertEquals(AgentErrorCode.NOT_GRANTED, refused.code)
        val notAllowed = harness.call("search", buildJsonObject { put("query", "flux"); put("documents", JsonArray(listOf(JsonPrimitive(inside.id)))) })
        assertEquals(0, notAllowed.getValue("passages").jsonArray.size)
    }
}
