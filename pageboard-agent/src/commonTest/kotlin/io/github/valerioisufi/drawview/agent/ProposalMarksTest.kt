package io.github.valerioisufi.drawview.agent

import io.github.valerioisufi.drawview.model.DrawStroke
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.StrokePoint
import io.github.valerioisufi.drawview.model.StrokeTool
import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.model.change.StrokeData
import io.github.valerioisufi.drawview.model.geometry.Rect
import io.github.valerioisufi.drawview.model.newElementId
import io.github.valerioisufi.drawview.store.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.store.KeyValueDocumentStore
import io.github.valerioisufi.drawview.store.OpenDocument
import io.github.valerioisufi.drawview.store.OpenDocuments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What every item of a proposal does on the pages, as the review draws it there
 * (docs/plans/agent-preview.md): where, and of which kind.
 */
class ProposalMarksTest {

    private class Setup(val open: OpenDocument, val agents: ExternalAgents, val documentId: String)

    private suspend fun setup(test: TestScope, scope: CoroutineScope): Setup {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = scope)
        val documents = OpenDocuments(store, StandardTestDispatcher(test.testScheduler), scope)
        val document = store.createDocument("Physics", 210f, 297f, PageBackground.Solid())
        return Setup(assertNotNull(documents.open(document.id)), ExternalAgents(documents, AgentEngines(), store), document.id)
    }

    /** Adds a page after the last one, as a model allowed to change the document at once does. */
    private suspend fun Setup.addPage() {
        agents.access.setEnabled(true)
        agents.access.grant(documentId)
        agents.access.setApplyDirectly(true)
        val arguments = buildJsonObject {
            put("document", documentId)
            put("title", "A page")
            put("items", JsonArray(listOf(buildJsonObject { put("kind", "add_page") })))
        }
        assertIs<ToolResult.Value>(agents.tools.call("propose", arguments, agents.access.connect("test").id))
    }

    private suspend fun Setup.addText(content: String, x: Float = 20f, y: Float = 40f, width: Float = 100f): DrawText = open.edit { session ->
        val text = DrawText().apply {
            text = content
            this.x = x; this.y = y; this.width = width; height = 10f; fontSize = 16f
        }
        session.submit(AddElements.of(session.document.pages[0], listOf(text)))
        text
    }

    private suspend fun Setup.addStroke(): DrawStroke = open.edit { session ->
        val page = session.document.pages[0]
        val data = StrokeData(
            id = newElementId(),
            points = listOf(StrokePoint(30f, 120f, 0.5f, 0L), StrokePoint(60f, 130f, 0.5f, 8L), StrokePoint(90f, 120f, 0.5f, 16L)),
            tool = StrokeTool.PRESSURE_PEN,
            size = 0.5f,
            color = 0xFF000000.toInt()
        )
        session.submit(AddElements(page.id, listOf(data)))
        page.strokes.first { it.id == data.id }
    }

    private suspend fun Setup.plan(vararg items: ProposalItem): ProposalPlan = open.edit { ProposalPlanner().plan(it.document, items.toList()) }

    private suspend fun Setup.marks(item: ProposalItem): List<PlannedMark> = plan(item).items.single().marks

    private suspend fun Setup.pageId(): String = open.edit { it.document.pages[0].id }

    private suspend fun <T> Setup.read(block: () -> T): T = open.edit { block() }

    private fun assertBox(expected: Rect, actual: Rect?, what: String) {
        assertNotNull(actual, what)
        for ((e, a) in listOf(expected.left to actual.left, expected.top to actual.top, expected.right to actual.right, expected.bottom to actual.bottom)) {
            assertTrue(kotlin.math.abs(e - a) < 0.01f, "$what: expected $expected but was $actual")
        }
    }

    /** The box of a text is as high as its words are laid out, not as it was stored: its corner and its width are checked. */
    private fun assertTextBox(left: Float, top: Float, width: Float, actual: Rect?, what: String) {
        assertNotNull(actual, what)
        assertBox(Rect(left, top, left + width, actual.bottom), actual, what)
        assertTrue(actual.height > 0f, what)
    }

    @Test
    fun aTextAddedIsMarkedWhereItWouldGoWithItsWords() = runTest {
        val setup = setup(this, backgroundScope)
        val plan = setup.plan(ProposalItem.InsertText("## Summary\nThe four equations.", color = "#C0392B", fontSize = 14f))
        val piece = plan.items.single().pieces.single()
        val mark = plan.items.single().marks.single()
        assertEquals(PlannedMark.Kind.ADDED, mark.kind)
        assertEquals(setup.pageId(), mark.pageId)
        assertEquals(piece.box, mark.box)
        assertEquals("## Summary\nThe four equations.", mark.markdown)
        assertEquals(14f, mark.fontSize)
        assertEquals(0xFFC0392B.toInt(), mark.color)
        assertEquals(null, mark.elementId)
    }

    @Test
    fun aTextReplacedIsMarkedOnItsBoxWithItsNewWords() = runTest {
        val setup = setup(this, backgroundScope)
        val text = setup.addText("gauss: flux = Q / eps0")
        val base = setup.read { text.base() }
        val marks = setup.marks(ProposalItem.ReplaceText(text.id, base, "**Gauss's law:** the flux through a closed surface."))
        val mark = marks.single()
        assertEquals(PlannedMark.Kind.CHANGED, mark.kind)
        assertEquals(text.id, mark.elementId)
        assertEquals("**Gauss's law:** the flux through a closed surface.", mark.markdown)
        assertEquals(text.color, mark.color)
        assertEquals(20f, mark.box.left)
        assertEquals(40f, mark.box.top)
    }

    @Test
    fun whatIsRemovedIsMarkedWhereItIs() = runTest {
        val setup = setup(this, backgroundScope)
        val text = setup.addText("see page 4 (old)")
        val stroke = setup.addStroke()
        val page = setup.pageId()
        val strokeBox = setup.read { setup.open.document.pages[0].boundsOf(stroke) }

        val removedText = setup.marks(ProposalItem.RemoveText(text.id, setup.read { text.base() })).single()
        assertEquals(PlannedMark.Kind.REMOVED, removedText.kind)
        assertTextBox(20f, 40f, 100f, removedText.box, "the text")

        val refs = setup.read { listOf(ElementRef(text.id, text.elementBase()), ElementRef(stroke.id, stroke.elementBase())) }
        val removed = setup.marks(ProposalItem.RemoveElements(page, refs))
        assertEquals(listOf(PlannedMark.Kind.REMOVED, PlannedMark.Kind.REMOVED), removed.map { it.kind })
        assertEquals(listOf(text.id, stroke.id), removed.map { it.elementId })
        assertBox(assertNotNull(strokeBox), removed[1].box, "the stroke")
    }

    @Test
    fun whatMovesIsMarkedFromWhereItIsToWhereItGoes() = runTest {
        val setup = setup(this, backgroundScope)
        val text = setup.addText("Maxwell's equations")
        val stroke = setup.addStroke()
        val page = setup.pageId()
        val strokeBox = assertNotNull(setup.read { setup.open.document.pages[0].boundsOf(stroke) })
        val refs = setup.read { listOf(ElementRef(text.id, text.elementBase()), ElementRef(stroke.id, stroke.elementBase())) }
        val marks = setup.marks(ProposalItem.PlaceElements(page, refs, dx = 12.5f, dy = -4f))
        assertEquals(listOf(PlannedMark.Kind.MOVED, PlannedMark.Kind.MOVED), marks.map { it.kind })
        assertTextBox(20f, 40f, 100f, marks[0].box, "the text now")
        assertTextBox(32.5f, 36f, 100f, marks[0].toBox, "the text after")
        assertEquals(marks[0].box.height, marks[0].toBox!!.height, 0.01f)
        assertBox(strokeBox, marks[1].box, "the stroke now")
        assertBox(Rect(strokeBox.left + 12.5f, strokeBox.top - 4f, strokeBox.right + 12.5f, strokeBox.bottom - 4f), marks[1].toBox, "the stroke after")
    }

    @Test
    fun aTextChangedInPlaceIsNotMarkedAsMovedAndOneGivenAnotherPlaceIs() = runTest {
        val setup = setup(this, backgroundScope)
        val text = setup.addText("Maxwell's equations")
        val base = setup.read { text.base() }
        val restyled = setup.marks(ProposalItem.RestyleText(text.id, base, color = "#C0392B"))
        assertEquals(listOf(PlannedMark.Kind.CHANGED), restyled.map { it.kind })
        assertEquals(0xFFC0392B.toInt(), restyled.single().color)
        assertEquals("Maxwell's equations", restyled.single().markdown)

        val updated = setup.marks(ProposalItem.UpdateText(text.id, base, markdown = "Maxwell", x = 60f, width = 80f))
        assertEquals(listOf(PlannedMark.Kind.MOVED, PlannedMark.Kind.CHANGED), updated.map { it.kind })
        assertEquals(20f, updated[0].box.left)
        assertEquals(60f, updated[0].toBox?.left)
        assertEquals(60f, updated[1].box.left)
        assertEquals("Maxwell", updated[1].markdown)
    }

    @Test
    fun whatChangesWhereItIsIsMarkedOnItsBox() = runTest {
        val setup = setup(this, backgroundScope)
        val stroke = setup.addStroke()
        val page = setup.pageId()
        val box = assertNotNull(setup.read { setup.open.document.pages[0].boundsOf(stroke) })
        val ref = setup.read { ElementRef(stroke.id, stroke.elementBase()) }
        for (item in listOf(ProposalItem.ReorderElements(page, listOf(ref), StackPlace.BACK), ProposalItem.RestyleStroke(stroke.id, ref.base, color = "#C0392B"))) {
            val mark = setup.marks(item).single()
            assertEquals(PlannedMark.Kind.CHANGED, mark.kind, "$item")
            assertEquals(stroke.id, mark.elementId)
            assertBox(box, mark.box, "$item")
        }
    }

    @Test
    fun aStrokeAndAnImageAddedAreMarkedWhereTheyWouldBe() = runTest {
        val setup = setup(this, backgroundScope)
        val page = setup.pageId()
        val stroke = setup.marks(ProposalItem.AddStroke(page, listOf(listOf(10f, 20f), listOf(40f, 60f), listOf(70f, 20f)), color = "#27AE60", width = 2f)).single()
        assertEquals(PlannedMark.Kind.ADDED, stroke.kind)
        assertEquals(listOf(10f, 20f, 40f, 60f, 70f, 20f), stroke.points)
        assertEquals(2f, stroke.strokeWidth)
        assertEquals(0xFF27AE60.toInt(), stroke.color)
        assertBox(Rect(9f, 19f, 71f, 61f), stroke.box, "the stroke")

        val image = setup.marks(ProposalItem.AddImage("/tmp/figure.png", page, AgentArea(30f, 100f, 100f, 50f), hash = "abc")).single()
        assertEquals(PlannedMark.Kind.ADDED, image.kind)
        assertBox(Rect(30f, 100f, 130f, 150f), image.box, "the image")
    }

    @Test
    fun thePagesAreMarkedWholeAndWhatIsNotOnAPageIsNot() = runTest {
        val setup = setup(this, backgroundScope)
        setup.addPage()
        val (first, second) = setup.read { setup.open.document.pages.map { it.id } }
        val base = setup.read { setup.open.document.pages[1].pageBase() }
        val whole = Rect(0f, 0f, 210f, 297f)

        val removed = setup.marks(ProposalItem.RemovePage(second, base)).single()
        assertEquals(PlannedMark.Kind.PAGE_REMOVED, removed.kind)
        assertEquals(second, removed.pageId)
        assertBox(whole, removed.box, "the page removed")
        for (item in listOf(ProposalItem.MovePage(second), ProposalItem.DuplicatePage(first), ProposalItem.SetPageFormat(second, base, background = "grid"))) {
            val mark = setup.marks(item).single()
            assertEquals(PlannedMark.Kind.PAGE_CHANGED, mark.kind, "$item")
            assertBox(whole, mark.box, "$item")
        }
        assertTrue(setup.marks(ProposalItem.AddPage()).isEmpty())
        assertTrue(setup.marks(ProposalItem.RenameDocument("Electromagnetism")).isEmpty())
    }

    @Test
    fun aStaleItemIsMarkedWhereWhatItWasAboutStillIs() = runTest {
        val setup = setup(this, backgroundScope)
        val text = setup.addText("see page 4 (old)")
        val plan = setup.plan(
            ProposalItem.RemoveText(text.id, "an old base"),
            ProposalItem.RemoveText("gone", "a base"),
            ProposalItem.RemovePage(setup.pageId(), "an old base")
        )
        assertEquals(listOf(true, true, true), plan.items.map { it.isStale })
        val still = plan.items[0].marks.single()
        assertEquals(PlannedMark.Kind.STALE, still.kind)
        assertTextBox(20f, 40f, 100f, still.box, "the text that changed")
        assertTrue(plan.items[1].marks.isEmpty(), "gone: nothing to mark")
        assertEquals(PlannedMark.Kind.STALE, plan.items[2].marks.single().kind)
    }

    @Test
    fun everyItemIsMarkedOnThePageAsTheItemsBeforeItLeaveIt() = runTest {
        val setup = setup(this, backgroundScope)
        val text = setup.addText("Maxwell's equations", y = 40f)
        val base = setup.read { text.base() }
        val plan = setup.plan(
            ProposalItem.RemoveText(text.id, base),
            ProposalItem.InsertText("A summary of the four equations, side by side.", near = null)
        )
        assertEquals(PlannedMark.Kind.REMOVED, plan.items[0].marks.single().kind)
        val added = plan.items[1].marks.single()
        assertEquals(PlannedMark.Kind.ADDED, added.kind)
        // The text removed leaves its room: the new one may go where it was
        assertEquals(plan.items[1].pieces.single().box, added.box)
    }
}
