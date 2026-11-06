package io.github.valerioisufi.drawview.agent

import io.github.valerioisufi.drawview.model.DrawCustomElement
import io.github.valerioisufi.drawview.model.DrawImage
import io.github.valerioisufi.drawview.model.DrawPdf
import io.github.valerioisufi.drawview.model.DrawStroke
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.StrokePoint
import io.github.valerioisufi.drawview.model.StrokeTool
import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.model.change.BoxPlacement
import io.github.valerioisufi.drawview.model.change.PlaceElements
import io.github.valerioisufi.drawview.model.change.StrokeData
import io.github.valerioisufi.drawview.model.change.UpdateTexts
import io.github.valerioisufi.drawview.model.change.toData
import io.github.valerioisufi.drawview.model.newElementId
import io.github.valerioisufi.drawview.store.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.store.KeyValueDocumentStore
import io.github.valerioisufi.drawview.store.OpenDocument
import io.github.valerioisufi.drawview.store.OpenDocuments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** What a model reads of the elements and the pages: each one with the base to change it by. */
class PageReadingTest {

    private class Setup(val agents: ExternalAgents, val connection: String, val documentId: String, val open: OpenDocument)

    private suspend fun setup(test: TestScope, scope: CoroutineScope): Setup {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = scope)
        val documents = OpenDocuments(store, StandardTestDispatcher(test.testScheduler), scope)
        val agents = ExternalAgents(documents, AgentEngines(), store)
        val document = store.createDocument("Physics", 210f, 297f, PageBackground.Solid())
        val open = assertNotNull(documents.open(document.id))
        agents.access.setEnabled(true)
        agents.access.grant(document.id)
        return Setup(agents, agents.access.connect("test").id, document.id, open)
    }

    private suspend fun Setup.call(name: String, arguments: JsonObject): JsonObject =
        assertIs<ToolResult.Value>(agents.tools.call(name, arguments, connection)).json.jsonObject

    private suspend fun Setup.readPage(strokes: Boolean = false, area: AgentArea? = null): JsonObject = call(
        "read_page",
        buildJsonObject {
            put("document", documentId)
            if (strokes) put("strokes", true)
            if (area != null) {
                putJsonObject("area") {
                    put("x", area.x); put("y", area.y); put("width", area.width); put("height", area.height)
                }
            }
        }
    )

    private suspend fun Setup.addStroke(x: Float, y: Float, points: Int = 20, color: Int = 0xFF1F5AA6.toInt()): DrawStroke = open.edit { session ->
        val stroke = StrokeData(
            id = newElementId(),
            points = (0 until points).map { StrokePoint(x = x + it * 0.5f, y = y + (it % 2), pressure = 0.5f, timestampMs = it * 8L) },
            tool = StrokeTool.PRESSURE_PEN,
            size = 0.5f,
            color = color
        ).toElement() as DrawStroke
        session.submit(AddElements.of(session.document.pages[0], listOf(stroke)))
        stroke
    }

    private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content

    private fun JsonObject.number(name: String) = getValue(name).jsonPrimitive.float

    @Test
    fun theStrokesAreListedOnlyWhenAsked() = runTest {
        val setup = setup(this, backgroundScope)
        val stroke = setup.addStroke(20f, 120f)
        val page = setup.readPage()
        assertEquals(1, page.getValue("strokes").jsonPrimitive.int)
        assertTrue("stroke_list" !in page)

        val listed = setup.readPage(strokes = true).getValue("stroke_list").jsonArray.single().jsonObject
        assertEquals(stroke.id, listed.string("id"))
        assertEquals("#1F5AA6", listed.string("color"))
        assertEquals("pressure_pen", listed.string("tool"))
        assertTrue(listed.number("x") <= 20f && listed.number("y") <= 120f && listed.number("width") >= 9f, "$listed")
        assertTrue(listed.string("base").isNotEmpty())
    }

    @Test
    fun theStrokesListedAreThoseCrossingTheArea() = runTest {
        val setup = setup(this, backgroundScope)
        val top = setup.addStroke(20f, 30f)
        setup.addStroke(20f, 250f)
        val listed = setup.readPage(strokes = true, area = AgentArea(0f, 0f, 210f, 100f)).getValue("stroke_list").jsonArray
        assertEquals(listOf(top.id), listed.map { it.jsonObject.string("id") })
    }

    @Test
    fun theStrokesListedAreBounded() = runTest {
        val setup = setup(this, backgroundScope)
        repeat(500 + 7) { setup.addStroke(10f + (it % 40) * 4f, 10f + (it / 40) * 5f, points = 3) }
        val page = setup.readPage(strokes = true)
        assertEquals(500, page.getValue("stroke_list").jsonArray.size)
        assertEquals(7, page.getValue("strokes_left_out").jsonPrimitive.int)
    }

    @Test
    fun aStrokeIsReadWithItsPointsOnThePage() = runTest {
        val setup = setup(this, backgroundScope)
        val stroke = setup.addStroke(20f, 120f, points = 21)
        val read = setup.call("read_stroke", buildJsonObject { put("document", setup.documentId); put("stroke", stroke.id) })
        val points = read.getValue("points").jsonArray.map { point -> point.jsonArray.map { it.jsonPrimitive.float } }
        assertEquals(21, points.size)
        assertEquals(listOf(20f, 120f), points.first())
        assertEquals(listOf(30f, 120f), points.last())
        assertEquals(stroke.id, read.getValue("stroke").jsonObject.string("id"))

        val fewer = setup.call("read_stroke", buildJsonObject { put("document", setup.documentId); put("stroke", stroke.id); put("max_points", 5) })
        val picked = fewer.getValue("points").jsonArray
        assertEquals(5, picked.size)
        assertEquals(21, fewer.getValue("points_in_all").jsonPrimitive.int)
        assertEquals(listOf(30f, 120f), picked.last().jsonArray.map { it.jsonPrimitive.float })

        val missing = assertFailsWith<AgentToolException> {
            setup.call("read_stroke", buildJsonObject { put("document", setup.documentId); put("stroke", "elsewhere") })
        }
        assertEquals(AgentErrorCode.NOT_FOUND, missing.code)
    }

    @Test
    fun theBaseOfAnElementChangesWithIt() = runTest {
        val setup = setup(this, backgroundScope)
        val image = setup.open.edit { session ->
            val image = DrawImage().apply { resourceId = "picture"; x = 30f; y = 200f; width = 50f; height = 40f }
            session.submit(AddElements.of(session.document.pages[0], listOf(image)))
            image
        }
        val before = setup.readPage().getValue("images").jsonArray.single().jsonObject.string("base")
        assertEquals(before, setup.readPage().getValue("images").jsonArray.single().jsonObject.string("base"))

        setup.open.edit { session ->
            val page = session.document.pages[0]
            session.submit(PlaceElements(page.id, listOf(BoxPlacement(image.id, 40f, 200f, 50f, 40f, 0f))))
        }
        assertNotEquals(before, setup.readPage().getValue("images").jsonArray.single().jsonObject.string("base"))
    }

    @Test
    fun theBaseOfAPageChangesWithWhatIsOnIt() = runTest {
        val setup = setup(this, backgroundScope)
        val before = setup.readPage().string("base")
        assertEquals(before, setup.call("read_document", buildJsonObject { put("document", setup.documentId) })
            .getValue("pages").jsonArray.single().jsonObject.string("base"))
        val text = DrawText().apply { text = "Gauss"; x = 10f; y = 20f; width = 100f; height = 10f; fontSize = 16f }
        setup.open.edit { session -> session.submit(AddElements.of(session.document.pages[0], listOf(text))) }
        val added = setup.readPage().string("base")
        assertNotEquals(before, added)
        setup.open.edit { session -> session.submit(UpdateTexts(session.document.pages[0].id, listOf(text.toData().copy(text = "Euler")))) }
        assertNotEquals(added, setup.readPage().string("base"))
    }

    @Test
    fun aTextIsReadWithItsStyle() = runTest {
        val setup = setup(this, backgroundScope)
        val text = DrawText().apply { text = "Gauss"; x = 10f; y = 20f; width = 100f; height = 10f; fontSize = 16f; color = 0x801F5AA6.toInt() }
        setup.open.edit { session -> session.submit(AddElements.of(session.document.pages[0], listOf(text))) }
        val read = setup.readPage().getValue("texts").jsonArray.single().jsonObject
        assertEquals("#1F5AA680", read.string("color"))
        assertEquals("start", read.string("alignment"))
        assertTrue("rotation" !in read)
    }

    @Test
    fun aScannedPageIsReadAsAPdfPageWithItsBase() = runTest {
        val setup = setup(this, backgroundScope)
        val pdf = DrawPdf(pdfPageIndex = 3).apply { resourceId = "scan" }
        setup.open.edit { session -> session.submit(AddElements.of(session.document.pages[0], listOf(pdf))) }
        val page = setup.readPage()
        val read = page.getValue("pdfs").jsonArray.single().jsonObject
        assertEquals(pdf.id, read.string("id"))
        assertEquals(3, read.getValue("pdf_page").jsonPrimitive.int)
        assertTrue(read.string("base").isNotEmpty())
        assertNull(page["pdf_text"])
    }

    @Test
    fun anElementOfAnotherKindIsReadByItsBox() = runTest {
        val setup = setup(this, backgroundScope)
        val custom = DrawCustomElement("chart").apply { x = 10f; y = 50f; width = 80f; height = 60f }
        setup.open.edit { session -> session.submit(AddElements.of(session.document.pages[0], listOf(custom))) }
        val read = setup.readPage().getValue("custom_elements").jsonArray.single().jsonObject
        assertEquals("chart", read.string("type"))
        assertEquals(60f, read.number("height"))
    }
}
