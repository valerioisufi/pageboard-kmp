package io.github.valerioisufi.pageboard.agent

import io.github.valerioisufi.pageboard.model.DrawImage
import io.github.valerioisufi.pageboard.model.DrawPage
import io.github.valerioisufi.pageboard.model.DrawStroke
import io.github.valerioisufi.pageboard.model.DrawText
import io.github.valerioisufi.pageboard.model.PageBackground
import io.github.valerioisufi.pageboard.model.StrokePoint
import io.github.valerioisufi.pageboard.model.StrokeTool
import io.github.valerioisufi.pageboard.model.TextAlignment
import io.github.valerioisufi.pageboard.model.change.AddElements
import io.github.valerioisufi.pageboard.model.change.BoxPlacement
import io.github.valerioisufi.pageboard.model.change.ChangeObserver
import io.github.valerioisufi.pageboard.model.change.ChangeOrigin
import io.github.valerioisufi.pageboard.model.change.CommittedChange
import io.github.valerioisufi.pageboard.model.change.DocumentSession
import io.github.valerioisufi.pageboard.model.change.Operation
import io.github.valerioisufi.pageboard.model.change.PlaceElements
import io.github.valerioisufi.pageboard.model.change.StrokeData
import io.github.valerioisufi.pageboard.model.newElementId
import io.github.valerioisufi.pageboard.store.InMemoryKeyValueStorage
import io.github.valerioisufi.pageboard.store.KeyValueDocumentStore
import io.github.valerioisufi.pageboard.store.OpenDocument
import io.github.valerioisufi.pageboard.store.OpenDocuments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The items of a proposal on the elements of a page: what they do, and what they refuse. */
class ElementItemsTest {

    private class Setup(
        val store: KeyValueDocumentStore,
        val agents: ExternalAgents,
        val connection: String,
        val documentId: String,
        val open: OpenDocument
    ) {
        val page: DrawPage get() = open.document.pages[0]
    }

    private class FakeImages : AgentImages {
        val kept = mutableListOf<String>()

        override suspend fun read(path: String): AgentImage? = if (path.endsWith(".png")) AgentImage(path, "hash", 400, 200) else null

        override suspend fun keep(path: String): AgentImage? = read(path)?.let { AgentImage("/kept/picture.png", it.hash, it.widthPx, it.heightPx) }?.also { kept += path }
    }

    private suspend fun setup(test: TestScope, scope: CoroutineScope, engines: AgentEngines = AgentEngines()): Setup {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = scope)
        val documents = OpenDocuments(store, StandardTestDispatcher(test.testScheduler), scope)
        val agents = ExternalAgents(documents, engines, store)
        val document = store.createDocument("Physics", 210f, 297f, PageBackground.Solid())
        val open = assertNotNull(documents.open(document.id))
        agents.access.setEnabled(true)
        agents.access.grant(document.id)
        agents.access.setApplyDirectly(true)
        return Setup(store, agents, agents.access.connect("test").id, document.id, open)
    }

    private suspend fun Setup.call(name: String, arguments: JsonObject): JsonObject =
        assertIs<ToolResult.Value>(agents.tools.call(name, arguments, connection)).json.jsonObject

    private suspend fun Setup.propose(vararg items: JsonObject): JsonObject = call(
        "propose",
        buildJsonObject {
            put("document", documentId)
            put("title", "A change")
            put("items", JsonArray(items.toList()))
        }
    )

    private suspend fun Setup.refused(vararg items: JsonObject): AgentToolException = assertFailsWith<AgentToolException> { propose(*items) }

    private suspend fun Setup.outcomes(answer: JsonObject): List<String> =
        answer.getValue("items").jsonArray.map { it.jsonPrimitive.content }

    /** The bases read_page gives, by id of an element. */
    private suspend fun Setup.bases(): Map<String, String> {
        val page = call("read_page", buildJsonObject { put("document", documentId); put("strokes", true) })
        return listOf("texts", "images", "stroke_list").flatMap { key ->
            page[key]?.jsonArray.orEmpty().map { it.jsonObject }
        }.associate { it.getValue("id").jsonPrimitive.content to it.getValue("base").jsonPrimitive.content }
    }

    private suspend fun Setup.refs(vararg ids: String): JsonArray {
        val bases = bases()
        return buildJsonArray {
            for (id in ids) add(buildJsonObject { put("id", id); put("base", bases.getValue(id)) })
        }
    }

    private suspend fun Setup.addImage(x: Float = 30f, y: Float = 200f): DrawImage = open.edit { session ->
        val image = DrawImage().apply { resourceId = "picture"; this.x = x; this.y = y; width = 50f; height = 40f }
        session.submit(AddElements.of(session.document.pages[0], listOf(image)))
        image
    }

    private suspend fun Setup.addStroke(x: Float = 20f, y: Float = 120f): DrawStroke = open.edit { session ->
        val stroke = StrokeData(
            id = newElementId(),
            points = (0 until 20).map { StrokePoint(x = x + it * 0.5f, y = y + (it % 2), pressure = 0.5f, timestampMs = it * 8L) },
            tool = StrokeTool.PRESSURE_PEN,
            size = 0.5f,
            color = 0xFF000000.toInt()
        ).toElement() as DrawStroke
        session.submit(AddElements.of(session.document.pages[0], listOf(stroke)))
        stroke
    }

    private suspend fun Setup.addText(content: String): DrawText = open.edit { session ->
        val text = DrawText().apply { text = content; x = 10f; y = 20f; width = 100f; height = 10f; fontSize = 16f }
        session.submit(AddElements.of(session.document.pages[0], listOf(text)))
        text
    }

    private inline fun item(kind: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("kind", kind)
        build()
    }

    private fun countOperations(open: OpenDocument): MutableList<CommittedChange> {
        val operations = mutableListOf<CommittedChange>()
        open.session.addObserver(ChangeObserver { if (it.origin == ChangeOrigin.LOCAL) operations += it })
        return operations
    }

    // ==========================================================
    // Each item
    // ==========================================================

    @Test
    fun elementsOfAnyKindAreRemovedAndKeptHidden() = runTest {
        val setup = setup(this, backgroundScope)
        val image = setup.addImage()
        val stroke = setup.addStroke()
        val answer = setup.propose(item("remove_elements") { put("page", setup.page.id); put("elements", setup.refs(image.id, stroke.id)) })
        assertEquals(listOf("accepted"), setup.outcomes(answer))
        assertTrue(setup.page.images.isEmpty())
        assertTrue(setup.page.strokes.isEmpty())
        assertEquals(setOf(image.id, stroke.id), setup.page.removedElements.map { it.id }.toSet())
    }

    @Test
    fun anElementChangedAfterItWasReadIsNotTouched() = runTest {
        val setup = setup(this, backgroundScope)
        setup.agents.access.setApplyDirectly(false)
        val image = setup.addImage()
        val refs = setup.refs(image.id)
        val id = setup.propose(item("remove_elements") { put("page", setup.page.id); put("elements", refs) }).getValue("proposal").jsonPrimitive.content
        // The user moves it meanwhile
        setup.open.edit { session -> session.submit(PlaceElements(session.document.pages[0].id, listOf(BoxPlacement(image.id, 60f, 200f, 50f, 40f, 0f)))) }
        setup.agents.decide(id, setOf(0))
        assertEquals(ItemOutcome.STALE, setup.agents.proposals.find(id)!!.entries.single().outcome)
        assertNull(image.removed)
        assertEquals(60f, image.x)
    }

    @Test
    fun elementsAreMovedAndScaledTogether() = runTest {
        val setup = setup(this, backgroundScope)
        val image = setup.addImage(x = 30f, y = 200f)
        val stroke = setup.addStroke(x = 30f, y = 180f)
        val strokeBox = stroke.bounds
        setup.propose(
            item("place_elements") {
                put("page", setup.page.id); put("elements", setup.refs(image.id, stroke.id)); put("dx", 10f); put("dy", -20f)
            }
        )
        assertEquals(40f, image.x)
        assertEquals(180f, image.y)
        val moved = setup.page.strokes.single().bounds
        assertEquals(strokeBox.left + 10f, moved.left, 0.01f)
        assertEquals(strokeBox.top - 20f, moved.top, 0.01f)

        setup.propose(item("place_elements") { put("page", setup.page.id); put("elements", setup.refs(image.id)); put("scale", 2f) })
        assertEquals(40f, image.x)
        assertEquals(100f, image.width)
        assertEquals(80f, image.height)
    }

    @Test
    fun aTextIsScaledWithItsFont() = runTest {
        val setup = setup(this, backgroundScope)
        val text = setup.addText("Gauss")
        setup.propose(item("place_elements") { put("page", setup.page.id); put("elements", setup.refs(text.id)); put("scale", 1.5f) })
        assertEquals(24f, text.fontSize)
        assertEquals(150f, text.width)
        assertEquals(10f, text.x, 0.01f)
    }

    @Test
    fun elementsAreNotMovedOutsideThePage() = runTest {
        val setup = setup(this, backgroundScope)
        val image = setup.addImage(x = 30f, y = 200f)
        val refused = setup.refused(item("place_elements") { put("page", setup.page.id); put("elements", setup.refs(image.id)); put("dy", 100f) })
        assertEquals(AgentErrorCode.REFUSED, refused.code)
        assertEquals(200f, image.y)
    }

    @Test
    fun elementsGoOnTopOrUnderTheOthers() = runTest {
        val setup = setup(this, backgroundScope)
        val first = setup.addImage(x = 30f, y = 200f)
        val second = setup.addImage(x = 40f, y = 210f)
        assertEquals(listOf(first.id, second.id), setup.page.stack.map { it.id })
        setup.propose(item("reorder_elements") { put("page", setup.page.id); put("elements", setup.refs(first.id)); put("to", "front") })
        assertEquals(listOf(second.id, first.id), setup.page.stack.map { it.id })
        setup.propose(item("reorder_elements") { put("page", setup.page.id); put("elements", setup.refs(first.id)); put("to", "back") })
        assertEquals(listOf(first.id, second.id), setup.page.stack.map { it.id })
    }

    @Test
    fun theStyleOfATextIsChanged() = runTest {
        val setup = setup(this, backgroundScope)
        val text = setup.addText("Gauss")
        setup.propose(
            item("restyle_text") {
                put("text", text.id); put("base", setup.bases().getValue(text.id)); put("color", "#C0392B"); put("font_size", 20f); put("alignment", "center")
            }
        )
        assertEquals(0xFFC0392B.toInt(), text.color)
        assertEquals(20f, text.fontSize)
        assertEquals(TextAlignment.CENTER, text.alignment)
        assertEquals("Gauss", text.text)

        val wrong = setup.refused(item("restyle_text") { put("text", text.id); put("base", setup.bases().getValue(text.id)); put("color", "red") })
        assertEquals(AgentErrorCode.REFUSED, wrong.code)
        val nothing = setup.refused(item("restyle_text") { put("text", text.id); put("base", setup.bases().getValue(text.id)) })
        assertEquals(AgentErrorCode.REFUSED, nothing.code)
    }

    @Test
    fun aStrokeIsDrawnThroughItsPoints() = runTest {
        val setup = setup(this, backgroundScope)
        setup.propose(
            item("add_stroke") {
                put("page", setup.page.id)
                putJsonArray("points") {
                    add(buildJsonArray { add(JsonPrimitive(20f)); add(JsonPrimitive(50f)) })
                    add(buildJsonArray { add(JsonPrimitive(120f)); add(JsonPrimitive(50f)) })
                }
                put("width", 1f)
            }
        )
        val stroke = setup.page.strokes.single()
        assertEquals(AGENT_TEXT_COLOR, stroke.brushConfig.color)
        assertEquals(1f, stroke.brushConfig.size)
        assertEquals(StrokeTool.PRESSURE_PEN, stroke.brushConfig.tool)
        assertTrue(stroke.bounds.left <= 20f && stroke.bounds.right >= 120f, "${stroke.bounds}")
    }

    @Test
    fun aStrokeOutsideThePageOrTooLongOrOfAnotherToolIsRefused() = runTest {
        val setup = setup(this, backgroundScope)
        fun stroke(points: List<List<Float>>, tool: String? = null) = item("add_stroke") {
            put("page", setup.page.id)
            putJsonArray("points") { points.forEach { point -> add(buildJsonArray { point.forEach { add(JsonPrimitive(it)) } }) } }
            if (tool != null) put("tool", tool)
        }
        assertEquals(AgentErrorCode.REFUSED, setup.refused(stroke(listOf(listOf(20f, 50f), listOf(300f, 50f)))).code)
        assertEquals(AgentErrorCode.REFUSED, setup.refused(stroke(List(2001) { listOf(20f, 50f) })).code)
        assertEquals(AgentErrorCode.REFUSED, setup.refused(stroke(listOf(listOf(20f, 50f), listOf(30f, 50f)), tool = "eraser")).code)
        assertEquals(AgentErrorCode.REFUSED, setup.refused(stroke(listOf(listOf(20f, 50f)))).code)
        assertTrue(setup.page.strokes.isEmpty())
    }

    @Test
    fun aStrokeIsRestyledAsACopyInItsPlace() = runTest {
        val setup = setup(this, backgroundScope)
        val stroke = setup.addStroke()
        val box = stroke.bounds
        setup.propose(item("restyle_stroke") { put("stroke", stroke.id); put("base", setup.bases().getValue(stroke.id)); put("color", "#C0392B"); put("width", 2f) })
        val shown = setup.page.strokes.filter { it.removed == null }.single()
        assertNotEquals(stroke.id, shown.id)
        assertEquals(0xFFC0392B.toInt(), shown.brushConfig.color)
        assertEquals(2f, shown.brushConfig.size)
        assertEquals(box.left, shown.bounds.left, 1f)
        assertTrue(stroke.removed != null)
    }

    @Test
    fun anImageIsAddedInItsProportions() = runTest {
        val images = FakeImages()
        val setup = setup(this, backgroundScope, AgentEngines(images = images))
        setup.propose(
            item("add_image") {
                put("path", "/Users/me/figure.png"); put("page", setup.page.id)
                putJsonObject("area") { put("x", 20f); put("y", 30f); put("width", 100f); put("height", 100f) }
            }
        )
        val image = setup.page.images.single()
        assertEquals(20f, image.x)
        assertEquals(30f, image.y)
        assertEquals(100f, image.width)
        assertEquals(50f, image.height)
        val resource = assertNotNull(setup.open.document.findResource(image.resourceId))
        assertEquals("/kept/picture.png", resource.path)
        assertEquals("hash", resource.hash)
        assertEquals(listOf("/Users/me/figure.png"), images.kept)
    }

    @Test
    fun anImageWithoutAnAreaGoesInTheFreeSpace() = runTest {
        val setup = setup(this, backgroundScope, AgentEngines(images = FakeImages()))
        setup.propose(item("add_image") { put("path", "/Users/me/figure.png") })
        val image = setup.page.images.single()
        assertEquals(100f, image.width)
        assertEquals(50f, image.height)
        assertTrue(image.x >= 10f && image.y >= 10f)
    }

    @Test
    fun anImageIsAddedOnlyWhenTheApplicationReadsIt() = runTest {
        val unavailable = setup(this, backgroundScope)
        assertEquals(AgentErrorCode.UNAVAILABLE, unavailable.refused(item("add_image") { put("path", "/Users/me/figure.png") }).code)
        val images = FakeImages()
        val setup = setup(this, backgroundScope, AgentEngines(images = images))
        assertEquals(AgentErrorCode.REFUSED, setup.refused(item("add_image") { put("path", "/Users/me/.ssh/id_ed25519") }).code)
        // A preview keeps nothing
        setup.call("preview_proposal", buildJsonObject {
            put("document", setup.documentId)
            put("items", JsonArray(listOf(item("add_image") { put("path", "/Users/me/figure.png") })))
        })
        assertTrue(images.kept.isEmpty())
        assertTrue(setup.page.images.isEmpty())
    }

    // ==========================================================
    // A batch
    // ==========================================================

    @Test
    fun aBatchOfElementsIsOneOperationUndoneAtOnce() = runTest {
        val setup = setup(this, backgroundScope, AgentEngines(images = FakeImages()))
        val image = setup.addImage()
        val stroke = setup.addStroke()
        val text = setup.addText("Gauss")
        val bases = setup.bases()
        val operations = countOperations(setup.open)
        setup.propose(
            item("place_elements") { put("page", setup.page.id); put("elements", setup.refs(image.id)); put("dx", 5f) },
            item("remove_elements") {
                put("page", setup.page.id)
                put("elements", buildJsonArray { add(buildJsonObject { put("id", stroke.id); put("base", bases.getValue(stroke.id)) }) })
            },
            item("restyle_text") { put("text", text.id); put("base", bases.getValue(text.id)); put("color", "#C0392B") },
            item("add_image") { put("path", "/Users/me/figure.png") }
        )
        assertEquals(1, operations.size)
        assertEquals(2, setup.page.images.count { it.removed == null })

        val batch = setup.agents.activity.batches.value.single()
        assertEquals(UndoOutcome.UNDONE, setup.agents.undo(batch.id))
        assertEquals(30f, image.x)
        assertNull(stroke.removed)
        assertEquals(0xFF000000.toInt(), text.color)
        assertEquals(listOf(image.id), setup.page.images.filter { it.removed == null }.map { it.id })
    }

    @Test
    fun aBatchOfElementsConvergesOnAnotherDevice() = runTest {
        val setup = setup(this, backgroundScope)
        val image = setup.addImage()
        val stroke = setup.addStroke()
        setup.open.flush()
        val other = DocumentSession(
            assertNotNull(setup.store.loadDocument(setup.documentId)),
            "other",
            setup.store.versionVector(setup.documentId)
        )
        val operations = countOperations(setup.open)
        val bases = setup.bases()
        setup.propose(
            item("place_elements") { put("page", setup.page.id); put("elements", setup.refs(image.id)); put("dx", 5f) },
            item("restyle_stroke") { put("stroke", stroke.id); put("base", bases.getValue(stroke.id)); put("color", "#C0392B") },
            item("add_stroke") {
                put("page", setup.page.id)
                putJsonArray("points") {
                    add(buildJsonArray { add(JsonPrimitive(20f)); add(JsonPrimitive(50f)) })
                    add(buildJsonArray { add(JsonPrimitive(60f)); add(JsonPrimitive(50f)) })
                }
            }
        )
        // What travels: the operation as it is written
        val sent = Json.decodeFromString(Operation.serializer(), Json.encodeToString(Operation.serializer(), operations.single().operation))
        assertTrue(other.receive(sent))
        fun shown(page: DrawPage) = page.elements().filter { it.removed == null }.map { it.id to (page.boundsOf(it)?.left ?: 0f) }.sortedBy { it.first }
        assertEquals(shown(setup.page), shown(other.document.pages[0]))
        assertFalse(other.document.pages[0].strokes.any { it.removed == null && it.id == stroke.id })
    }

    @Test
    fun anElementIsChangedByOneItemOnly() = runTest {
        val setup = setup(this, backgroundScope)
        val image = setup.addImage()
        val refs = setup.refs(image.id)
        val refused = setup.refused(
            item("place_elements") { put("page", setup.page.id); put("elements", refs); put("dx", 5f) },
            item("remove_elements") { put("page", setup.page.id); put("elements", refs) }
        )
        assertEquals(AgentErrorCode.REFUSED, refused.code)
    }

    @Test
    fun anElementOnAnotherPageIsNotFound() = runTest {
        val setup = setup(this, backgroundScope)
        val refused = setup.refused(
            item("remove_elements") {
                put("page", setup.page.id)
                put("elements", buildJsonArray { add(buildJsonObject { put("id", "elsewhere"); put("base", "x") }) })
            }
        )
        assertEquals(AgentErrorCode.NOT_FOUND, refused.code)
    }
}
