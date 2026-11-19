package io.github.valerioisufi.drawview.agent

import io.github.valerioisufi.drawview.model.DrawImage
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.FractionalIndex
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.DrawTextMarkup
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.TextMarkupKind
import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.model.change.AddPage
import io.github.valerioisufi.drawview.model.change.AddResource
import io.github.valerioisufi.drawview.model.DrawResource
import io.github.valerioisufi.drawview.model.change.RemovePage
import io.github.valerioisufi.drawview.model.change.SetPageFormat
import io.github.valerioisufi.drawview.model.geometry.Rect
import io.github.valerioisufi.drawview.store.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.store.KeyValueDocumentStore
import io.github.valerioisufi.drawview.store.OpenDocument
import io.github.valerioisufi.drawview.store.OpenDocuments
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** What a model reads beyond the texts and the strokes: marks, backgrounds, dates, removed pages, image files (`docs/plans/agent-coverage.md`). */
class CoverageReadingTest {

    private class FakeImages(val files: Map<String, ByteArray>) : AgentImages {
        val asked = mutableListOf<Int>()
        override suspend fun read(path: String): AgentImage? = null
        override suspend fun keep(path: String): AgentImage? = null
        override suspend fun bytes(path: String, maxBytes: Int): ByteArray? {
            asked += maxBytes
            return files[path]?.takeIf { it.size <= maxBytes }
        }
    }

    private class Setup(val agents: ExternalAgents, val connection: String, val documentId: String, val open: OpenDocument)

    private suspend fun setup(test: TestScope, scope: CoroutineScope, images: AgentImages? = null): Setup {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = scope)
        val documents = OpenDocuments(store, StandardTestDispatcher(test.testScheduler), scope)
        val agents = ExternalAgents(documents, AgentEngines(images = images), store)
        val document = store.createDocument("Physics", 210f, 297f, PageBackground.Grid(spacingMm = 6f))
        val open = assertNotNull(documents.open(document.id))
        agents.access.setEnabled(true)
        agents.access.grant(document.id)
        return Setup(agents, agents.access.connect("test").id, document.id, open)
    }

    private suspend fun Setup.call(name: String, vararg arguments: Pair<String, String>): JsonObject =
        assertIs<ToolResult.Value>(agents.tools.call(name, buildJsonObject {
            put("document", documentId)
            arguments.forEach { (key, value) -> put(key, value) }
        }, connection)).json.jsonObject

    private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content

    @Test
    fun theMarksOfThePdfTextAreRead() = runTest {
        val setup = setup(this, backgroundScope)
        val mark = setup.open.edit { session ->
            val page = session.document.pages[0]
            val mark = DrawTextMarkup(
                resourceId = "slides", pdfPageIndex = 0, pdfWidthPt = 595f, pdfHeightPt = 842f,
                startIndex = 4, endIndex = 9, text = "Gauss", rects = listOf(Rect(100f, 200f, 160f, 214f)),
                kind = TextMarkupKind.HIGHLIGHT, color = 0x80FFEB3B.toInt()
            )
            session.submit(AddElements.of(page, listOf(mark)))
            mark
        }
        val read = setup.call("read_page").getValue("markups").jsonArray.single().jsonObject
        assertEquals(mark.id, read.string("id"))
        assertEquals("highlight", read.string("kind"))
        assertEquals("Gauss", read.string("text"))
        assertEquals("#FFEB3B80", read.string("color"))
        // 595 points over 210 millimeters
        assertEquals(35.3f, read.getValue("x").jsonPrimitive.float, 0.2f)
        assertEquals(21.2f, read.getValue("width").jsonPrimitive.float, 0.2f)
        assertTrue(read.string("base").isNotEmpty())
    }

    @Test
    fun theBackgroundIsReadWithItsSpacingAndColor() = runTest {
        val setup = setup(this, backgroundScope)
        val inherited = setup.call("read_page")
        assertEquals("document", inherited.string("background"))
        assertEquals(6f, inherited.getValue("spacing_mm").jsonPrimitive.float)
        assertEquals("#FFFFFF", inherited.string("background_color"))

        setup.open.edit { session ->
            val page = session.document.pages[0]
            session.submit(SetPageFormat(page.id, page.widthMm, page.heightMm, PageBackground.Ruled(backgroundColor = 0xFFFFF8E1.toInt(), spacingMm = 7f)))
        }
        val ruled = setup.call("read_page")
        assertEquals("ruled", ruled.string("background"))
        assertEquals(7f, ruled.getValue("spacing_mm").jsonPrimitive.float)
        assertEquals("#FFF8E1", ruled.string("background_color"))
    }

    @Test
    fun aDocumentTellsItsFormatItsDatesAndItsRemovedPages() = runTest {
        val setup = setup(this, backgroundScope)
        val read = setup.call("read_document")
        val format = read.getValue("new_page_format").jsonObject
        assertEquals(210f, format.getValue("width_mm").jsonPrimitive.float)
        assertEquals("grid", format.string("background"))
        assertEquals(6f, format.getValue("spacing_mm").jsonPrimitive.float)
        assertTrue(read.getValue("created_at").jsonPrimitive.long > 0L, "$read")
        assertTrue("is_shared" !in read && "has_pdf" !in read && "removed_pages" !in read, "$read")

        val listed = setup.agents.tools.call("list_documents", JsonObject(emptyMap()), setup.connection)
        val entry = assertIs<ToolResult.Value>(listed).json.jsonObject.getValue("documents").jsonArray.single().jsonObject
        assertEquals(setup.documentId, entry.string("id"))
        assertTrue(entry.getValue("modified_at").jsonPrimitive.long > 0L, "$entry")
    }

    @Test
    fun theRemovedPagesAreListed() = runTest {
        val setup = setup(this, backgroundScope)
        val removed = setup.open.edit { session ->
            val document = session.document
            val second = document.pages[0].let { first ->
                val page = DrawPage(widthMm = 148f, heightMm = 210f, orderKey = FractionalIndex.between(first.orderKey, null))
                session.submit(AddPage.of(page))
                document.pages[1]
            }
            val text = DrawText().apply { text = "Maxwell's equations"; x = 10f; y = 20f; width = 100f; height = 10f; fontSize = 16f }
            session.submit(AddElements.of(second, listOf(text)))
            session.submit(RemovePage(second.id))
            second.id
        }
        val read = setup.call("read_document")
        assertEquals(1, read.getValue("removed_pages").jsonPrimitive.int)
        val listed = setup.call("list_removed_pages").getValue("pages").jsonArray.single().jsonObject
        assertEquals(removed, listed.string("id"))
        assertEquals(1, listed.getValue("former_index").jsonPrimitive.int)
        assertEquals(1, listed.getValue("texts").jsonPrimitive.int)
        assertEquals(148f, listed.getValue("width_mm").jsonPrimitive.float)
        assertEquals("Maxwell's equations", listed.string("text_start"))
        assertTrue(listed.getValue("removed_at").jsonPrimitive.long > 0L)
    }

    @Test
    fun theFileOfAnImageIsReadBounded() = runTest {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
        val images = FakeImages(mapOf("/files/picture.png" to png, "/files/notes.txt" to "hello".encodeToByteArray()))
        val setup = setup(this, backgroundScope, images)
        val (picture, text) = setup.open.edit { session ->
            session.submit(AddResource.of("picture", DrawResource.ResourceType.IMAGE, "/files/picture.png", ""))
            session.submit(AddResource.of("notes", DrawResource.ResourceType.IMAGE, "/files/notes.txt", ""))
            val picture = DrawImage().apply { resourceId = "picture"; x = 30f; y = 200f; width = 50f; height = 40f }
            val text = DrawImage().apply { resourceId = "notes"; x = 30f; y = 100f; width = 50f; height = 40f }
            session.submit(AddElements.of(session.document.pages[0], listOf(picture, text)))
            picture.id to text.id
        }
        val read = setup.agents.tools.call("read_image", buildJsonObject { put("document", setup.documentId); put("image", picture) }, setup.connection)
        val image = assertIs<ToolResult.Image>(read)
        assertEquals("image/png", image.mediaType)
        assertContentEquals(png, image.bytes)
        assertEquals(16 * 1024 * 1024, images.asked.single())

        val unknown = assertFailsWith<AgentToolException> {
            setup.agents.tools.call("read_image", buildJsonObject { put("document", setup.documentId); put("image", text) }, setup.connection)
        }
        assertEquals(AgentErrorCode.UNAVAILABLE, unknown.code)
        val missing = assertFailsWith<AgentToolException> {
            setup.agents.tools.call("read_image", buildJsonObject { put("document", setup.documentId); put("image", "elsewhere") }, setup.connection)
        }
        assertEquals(AgentErrorCode.NOT_FOUND, missing.code)
    }

    @Test
    fun readImageIsOfferedOnlyWithTheImagesOfTheApplication() = runTest {
        val without = setup(this, backgroundScope)
        assertTrue(without.agents.tools.tools.none { it.name == "read_image" })
        val with = setup(this, backgroundScope, FakeImages(emptyMap()))
        assertTrue(with.agents.tools.tools.any { it.name == "read_image" })
        assertTrue(with.agents.tools.tools.any { it.name == "list_removed_pages" })
    }
}
