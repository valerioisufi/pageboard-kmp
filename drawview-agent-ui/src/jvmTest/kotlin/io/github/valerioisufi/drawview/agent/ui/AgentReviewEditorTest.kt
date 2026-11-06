package io.github.valerioisufi.drawview.agent.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.drawview.agent.AgentEngines
import io.github.valerioisufi.drawview.agent.ChosenPlace
import io.github.valerioisufi.drawview.agent.ExternalAgents
import io.github.valerioisufi.drawview.agent.ItemOutcome
import io.github.valerioisufi.drawview.agent.ToolResult
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.model.geometry.Rect
import io.github.valerioisufi.drawview.session.DocumentEditorViewModel
import io.github.valerioisufi.drawview.session.EditorSessionScreen
import io.github.valerioisufi.drawview.store.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.store.KeyValueDocumentStore
import io.github.valerioisufi.drawview.store.OpenDocuments
import io.github.valerioisufi.drawview.ui.adaptive.AdaptiveRoot
import io.github.valerioisufi.drawview.ui.theme.DrawViewTheme
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The review in a real editor: a proposal opens the panel, accepted it changes the document, one undo
 * of the editor takes it all back, and what is being written is never overwritten.
 */
@OptIn(ExperimentalTestApi::class)
class AgentReviewEditorTest {

    @BeforeTest
    fun setUp() {
        Locale.setDefault(Locale.US)
    }

    private class Scene(
        val agents: ExternalAgents,
        val documentId: String,
        val connection: String,
        val controller: DrawViewController,
        val text: DrawText
    ) {
        suspend fun call(name: String, arguments: JsonObject): JsonObject =
            assertIs<ToolResult.Value>(agents.tools.call(name, arguments, connection)).json.jsonObject

        /** Proposes to replace [text] and to add a text; the id of the proposal. */
        fun proposeChanges(): String = runBlocking {
            val read = call("read_page", buildJsonObject { put("document", documentId) })
            val base = read.getValue("texts").jsonArray.first().jsonObject.getValue("base").jsonPrimitive.content
            call(
                "propose",
                buildJsonObject {
                    put("document", documentId)
                    put("title", "Tidy the notes")
                    put(
                        "items",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("kind", "replace_text")
                                    put("text", text.id)
                                    put("base", base)
                                    put("markdown", "From the model")
                                },
                                buildJsonObject {
                                    put("kind", "insert_text")
                                    put("markdown", "Added by the model")
                                }
                            )
                        )
                    )
                }
            ).getValue("proposal").jsonPrimitive.content
        }

        val texts: List<String> get() = controller.document.pages.flatMap { it.texts }.filter { it.removed == null }.map { it.text }
    }

    private fun scene(block: DesktopComposeUiTest.(Scene) -> Unit) = runDesktopComposeUiTest(width = 1280, height = 800) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = scope)
        val documents = OpenDocuments(store, dispatcher = Dispatchers.Unconfined)
        val opened = mutableListOf<String>()
        val agents = ExternalAgents(documents, AgentEngines(editors = SessionAgentEditors { opened += it }), store)
        val ui = AgentReviewUi(agents)
        val document = runBlocking { store.createDocument("Physics", 210f, 297f, PageBackground.Solid()) }
        runBlocking {
            agents.access.setEnabled(true)
            agents.access.grant(document.id)
        }
        val viewModel = DocumentEditorViewModel(documents)
        setContent {
            DrawViewTheme(darkTheme = false, dynamicColor = false) {
                AdaptiveRoot(modifier = Modifier.fillMaxSize()) {
                    viewModel.session?.let { EditorSessionScreen(it, onClose = {}, extension = ui.editorExtension) }
                }
            }
        }
        waitForIdle()
        viewModel.openDocument(document.id)
        awaitInRealTime { viewModel.session?.controller != null }
        waitForIdle()
        val controller = viewModel.session!!.controller!!
        val text = DrawText().apply {
            this.text = "Old text"
            x = 20f; y = 20f; width = 100f; height = 10f; fontSize = 16f
        }
        controller.session.submit(AddElements.of(controller.document.pages.first(), listOf(text)))
        waitForIdle()
        try {
            block(Scene(agents, document.id, agents.access.connect("Test client").id, controller, text))
        } finally {
            scope.cancel()
        }
    }

    private fun DesktopComposeUiTest.awaitInRealTime(timeoutMillis: Long = 20_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "Timed out after $timeoutMillis ms" }
            Thread.sleep(20)
            mainClock.advanceTimeByFrame()
        }
    }

    private fun DesktopComposeUiTest.awaitPanel() = awaitInRealTime {
        onAllNodesWithText("Apply selected").fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun aProposalOpensThePanelAndOneUndoTakesItBack() = scene { scene ->
        val id = scene.proposeChanges()
        awaitPanel()
        // Nothing yet
        assertEquals(listOf("Old text"), scene.texts)

        onNodeWithText("Apply selected").performClick()
        awaitInRealTime { !scene.agents.proposals.find(id)!!.isWaiting }
        assertEquals(setOf("From the model", "Added by the model"), scene.texts.toSet())
        assertEquals(listOf(ItemOutcome.ACCEPTED, ItemOutcome.ACCEPTED), scene.agents.proposals.find(id)!!.entries.map { it.outcome })
        assertTrue(scene.controller.historyManager.canUndo)

        scene.controller.historyManager.undo()
        assertEquals(listOf("Old text"), scene.texts)
    }

    @Test
    fun rejectingChangesNothing() = scene { scene ->
        val id = scene.proposeChanges()
        awaitPanel()
        onNodeWithText("Reject all").performClick()
        awaitInRealTime { !scene.agents.proposals.find(id)!!.isWaiting }
        assertEquals(listOf("Old text"), scene.texts)
        assertEquals(listOf(ItemOutcome.REJECTED, ItemOutcome.REJECTED), scene.agents.proposals.find(id)!!.entries.map { it.outcome })
    }

    @Test
    fun whatIsBeingWrittenIsNotOverwritten() = scene { scene ->
        val id = scene.proposeChanges()
        awaitPanel()
        // The user starts writing in that text meanwhile
        scene.controller.textEditorManager.startEditing(scene.text, 0)
        waitForIdle()
        scene.controller.textEditorManager.updateValue(TextFieldValue("Written by the user"))
        waitForIdle()

        runBlocking { scene.agents.decide(id, setOf(0, 1)) }
        assertFalse(scene.controller.textEditorManager.isEditing)
        assertEquals("Written by the user", scene.text.text)
        val outcomes = scene.agents.proposals.find(id)!!.entries.map { it.outcome }
        assertEquals(listOf(ItemOutcome.STALE, ItemOutcome.ACCEPTED), outcomes)
    }

    @Test
    fun applyingDirectlyChangesAnOpenDocumentAtOnceAndCanBeUndone() = scene { scene ->
        runBlocking { scene.agents.access.setApplyDirectly(true) }
        val id = scene.proposeChanges()
        assertFalse(scene.agents.proposals.find(id)!!.isWaiting)
        assertEquals(setOf("From the model", "Added by the model"), scene.texts.toSet())
        scene.controller.historyManager.undo()
        assertEquals(listOf("Old text"), scene.texts)
    }

    @Test
    fun aTextThatWouldNotFitIsToldInTheReview() = scene { scene ->
        val base = runBlocking {
            scene.call("read_page", buildJsonObject { put("document", scene.documentId) })
                .getValue("texts").jsonArray.first().jsonObject.getValue("base").jsonPrimitive.content
        }
        runBlocking {
            scene.call(
                "propose",
                buildJsonObject {
                    put("document", scene.documentId)
                    put("title", "Far too long")
                    put(
                        "items",
                        JsonArray(listOf(buildJsonObject {
                            put("kind", "replace_text")
                            put("text", scene.text.id)
                            put("base", base)
                            put("markdown", (1..200).joinToString("\n\n") { "Line $it" })
                        }))
                    )
                }
            )
        }
        awaitPanel()
        awaitInRealTime { onAllNodesWithText("Would pass the end of the page", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun DesktopComposeUiTest.showNewText() {
        awaitInRealTime { onAllNodesWithText("Show on the page").fetchSemanticsNodes().size == 2 }
        onAllNodesWithText("Show on the page")[1].performClick()
    }

    @Test
    fun aTextIsShownOnThePageAndGoesWhereItIsPut() = scene { scene ->
        val id = scene.proposeChanges()
        awaitPanel()
        showNewText()
        val placement = scene.controller.placementManager
        awaitInRealTime { placement.isActive }
        // Shown, not applied
        assertEquals(listOf("Old text"), scene.texts)
        val box = assertNotNull(placement.spots.single()).box
        assertEquals("Added by the model", assertNotNull(placement.request).items.single().preview.text)

        assertTrue(placement.confirm())
        waitForIdle()
        assertEquals(listOf("Old text"), scene.texts)
        awaitInRealTime { onAllNodesWithText("Goes where you put it").fetchSemanticsNodes().isNotEmpty() }

        onNodeWithText("Apply selected").performClick()
        awaitInRealTime { !scene.agents.proposals.find(id)!!.isWaiting }
        val added = scene.controller.document.pages.flatMap { it.texts }.single { it.text == "Added by the model" }
        assertEquals(box.left, added.x)
        assertEquals(box.top, added.y)
        scene.controller.historyManager.undo()
        assertEquals(listOf("Old text"), scene.texts)
    }

    @Test
    fun aTextShownAndCancelledChangesNothing() = scene { scene ->
        val id = scene.proposeChanges()
        awaitPanel()
        showNewText()
        val placement = scene.controller.placementManager
        awaitInRealTime { placement.isActive }

        placement.cancel()
        waitForIdle()

        assertEquals(listOf("Old text"), scene.texts)
        assertTrue(scene.agents.proposals.find(id)!!.isWaiting)
        assertTrue(onAllNodesWithText("Goes where you put it").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aPlaceChosenIsTheOneApplied() = scene { scene ->
        val id = scene.proposeChanges()
        val page = scene.controller.document.pages.first().id
        runBlocking { scene.agents.decide(id, setOf(1), mapOf(1 to listOf(ChosenPlace(page, Rect(30f, 200f, 100f, 220f))))) }

        val added = scene.controller.document.pages.flatMap { it.texts }.single { it.text == "Added by the model" }
        assertEquals(30f, added.x)
        assertEquals(200f, added.y)
        assertEquals(70f, added.width)
        scene.controller.historyManager.undo()
        assertEquals(listOf("Old text"), scene.texts)
    }

    /** The heights of an editor; how long the largest preview takes is in `docs/agent-placement.md`. */
    @Test
    fun aPreviewInAnEditorIsMeasured() = scene { scene ->
        val answer = runBlocking {
            scene.call(
                "preview_proposal",
                buildJsonObject {
                    put("document", scene.documentId)
                    put("items", JsonArray(listOf(buildJsonObject {
                        put("kind", "insert_text")
                        put("markdown", "New")
                    })))
                }
            )
        }
        assertEquals("true", answer.getValue("measured").jsonPrimitive.content)
    }

    @Test
    fun aPageIsDrawnForAModel() = scene { scene ->
        val page = scene.controller.document.pages.first().id
        val png = runBlocking { scene.controller.renderPageImage(page, 256) }
        assertTrue(png != null && png.size > 8)
        // The signature of a PNG
        assertEquals(0x89.toByte(), png[0])
        assertEquals('P'.code.toByte(), png[1])
        assertEquals(null, runBlocking { scene.controller.renderPageImage("nowhere", 256) })
    }
}
