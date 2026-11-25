package io.github.valerioisufi.pageboard.model

import io.github.valerioisufi.pageboard.model.change.AddElements
import io.github.valerioisufi.pageboard.model.change.AssignElementsLayer
import io.github.valerioisufi.pageboard.model.change.DocumentSession
import io.github.valerioisufi.pageboard.model.change.HybridClock
import io.github.valerioisufi.pageboard.model.change.LockElements
import io.github.valerioisufi.pageboard.model.change.Operation
import io.github.valerioisufi.pageboard.model.change.OperationId
import io.github.valerioisufi.pageboard.model.change.RemovePageLayer
import io.github.valerioisufi.pageboard.model.change.UpsertPageLayer
import io.github.valerioisufi.pageboard.model.change.snapshot
import io.github.valerioisufi.pageboard.platform.currentTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageLayerAndLockTest {

    private fun stroke(id: String): StrokeElement = StrokeElement(
        id = id,
        mesh = StrokeMesh(
            positions = floatArrayOf(),
            indices = shortArrayOf(),
            color = -16777216,
            tool = StrokeTool.PRESSURE_PEN,
            size = 0.5f,
            points = listOf(StrokePoint(10f, 10f, 0.5f, 1L), StrokePoint(20f, 20f, 0.5f, 16L))
        ),
        brushConfig = BrushConfig(StrokeTool.PRESSURE_PEN, 0.5f, -16777216)
    )

    @Test
    fun lockingElementsSupportsMixedStateUndoAndRedo() {
        val doc = Document()
        val session = DocumentSession(doc, replica = "ada")
        val page = doc.pages.single()
        val s1 = stroke("s1")
        val s2 = stroke("s2")
        session.submit(AddElements.of(page, listOf(s1, s2)))

        session.submit(LockElements(page.id, listOf("s1"), true))
        assertTrue(page.isElementLocked(s1))
        assertFalse(page.isElementLocked(s2))

        val inverse = assertNotNull(session.submit(LockElements(page.id, listOf("s1", "s2"), true)))
        assertTrue(page.isElementLocked(s1))
        assertTrue(page.isElementLocked(s2))

        session.submit(inverse)
        assertTrue(page.isElementLocked(s1))
        assertFalse(page.isElementLocked(s2))
    }

    @Test
    fun pageStackRespectsLayerOrderVisibilityAndLocking() {
        val doc = Document()
        val session = DocumentSession(doc, replica = "ada")
        val page = doc.pages.single()
        val sBottom = stroke("bottom")
        val sTop = stroke("top")
        val sUnlayered = stroke("unlayered")
        session.submit(AddElements.of(page, listOf(sTop, sBottom, sUnlayered)))

        val bgKey = FractionalIndex.between(null, null)
        val fgKey = FractionalIndex.between(bgKey, null)
        session.submit(UpsertPageLayer(page.id, PageLayerData("bg", "Background", bgKey, visible = true, locked = true)))
        session.submit(UpsertPageLayer(page.id, PageLayerData("fg", "Foreground", fgKey, visible = true, locked = false)))
        session.submit(AssignElementsLayer(page.id, listOf("sBottom"), "bg")) // unknown id ignored safely
        session.submit(AssignElementsLayer(page.id, listOf("bottom"), "fg"))
        session.submit(AssignElementsLayer(page.id, listOf("top"), "bg"))

        // Unlayered comes first, then bg ("top"), then fg ("bottom") regardless of element order within each bucket
        assertEquals(listOf("unlayered", "top", "bottom"), page.stack.map { it.id })
        assertTrue(page.isElementLocked(sTop))
        assertFalse(page.isElementLocked(sBottom))

        // Hide bg layer: element disappears from page.stack and paintOrder
        session.submit(UpsertPageLayer(page.id, PageLayerData("bg", "Background", bgKey, visible = false, locked = true)))
        assertFalse(page.isElementVisible(sTop))
        assertEquals(listOf("unlayered", "bottom"), page.stack.map { it.id })
        assertEquals(listOf("unlayered", "bottom"), page.snapshotContent().paintOrder.map { it.id })

        // Remove fg layer: unlayered and fg element now sit at rank 0 together and layer moves to removedLayers
        val removeInv = assertNotNull(session.submit(RemovePageLayer(page.id, "fg")))
        assertNull(page.findLayer("fg"))
        assertTrue("bottom" in page.stack.map { it.id })

        // Undo layer removal restores fg layer and re-ranks elements
        session.submit(removeInv)
        assertNotNull(page.findLayer("fg"))
        assertEquals(listOf("unlayered", "bottom"), page.stack.map { it.id })
    }

    @Test
    fun concurrentLayerAndLockOperationsConvergeAndRoundTripSnapshot() {
        val base = Document()
        val p = base.pages.single()
        val s1 = stroke("s1")
        val s2 = stroke("s2")
        val s0 = DocumentSession(base, replica = "init")
        val initOps = listOf(s0.submitAndCapture(AddElements.of(p, listOf(s1, s2))))

        val snap = base.snapshot()
        val docA = snap.toDocument()
        val docB = snap.toDocument()
        val sessA = DocumentSession(docA, replica = "ada")
        val sessB = DocumentSession(docB, replica = "bob")
        initOps.forEach { op -> sessA.clock.observe(op.stamp); sessB.clock.observe(op.stamp) }

        val key1 = FractionalIndex.between(null, null)
        val key2 = FractionalIndex.between(key1, null)

        val opA1 = sessA.submitAndCapture(UpsertPageLayer(p.id, PageLayerData("l1", "Ink", key1, visible = true, locked = false)))
        val opA2 = sessA.submitAndCapture(AssignElementsLayer(p.id, listOf("s1"), "l1"))
        val opA3 = sessA.submitAndCapture(LockElements(p.id, listOf("s1"), true))

        val opB1 = sessB.submitAndCapture(UpsertPageLayer(p.id, PageLayerData("l2", "Guides", key2, visible = false, locked = true)))
        val opB2 = sessB.submitAndCapture(AssignElementsLayer(p.id, listOf("s1", "s2"), "l2"))
        val opB3 = sessB.submitAndCapture(LockElements(p.id, listOf("s1"), false))

        // Deliver in opposite causal orders
        listOf(opB1, opB2, opB3).forEach { assertTrue(sessA.receive(it)) }
        listOf(opA1, opA2, opA3).forEach { assertTrue(sessB.receive(it)) }

        assertEquals(docA.snapshot(), docB.snapshot())

        // Snapshot round-trip preserves layers and element lock/layer stamps
        val restored = docA.snapshot().toDocument()
        assertEquals(docA.snapshot(), restored.snapshot())
    }

    @Test
    fun sessionRejectsUntrustedLayerAndLockOperations() {
        val now = currentTimeMillis()
        val doc = Document()
        val sess = DocumentSession(doc, replica = "ada")
        val pageId = doc.pages.single().id

        val badOps = listOf(
            UpsertPageLayer(pageId, PageLayerData("", "Bad", "V")),
            UpsertPageLayer(pageId, PageLayerData("id", "x".repeat(PageLayer.MAX_NAME_LENGTH + 1), "V")),
            UpsertPageLayer(pageId, PageLayerData("id", "BadZeroKey", "V0")),
            UpsertPageLayer(pageId, PageLayerData("id", "BadChars", "V!")),
            AssignElementsLayer(pageId, listOf(""), "l1"),
            LockElements("", listOf("e1"), true),
            LockElements(pageId, listOf(""), true)
        )
        for ((index, change) in badOps.withIndex()) {
            val op = Operation(OperationId("bob", (index + 1).toLong()), Stamp(now + index, 0, "bob"), change)
            assertFalse(sess.admits(op), "Should reject invalid change $change")
        }
    }

    private fun DocumentSession.submitAndCapture(change: io.github.valerioisufi.pageboard.model.change.DocumentChange): Operation {
        val captured = mutableListOf<Operation>()
        val observer = io.github.valerioisufi.pageboard.model.change.ChangeObserver { captured += it.operation }
        addObserver(observer)
        submit(change)
        removeObserver(observer)
        return captured.single()
    }
}
