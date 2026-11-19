package io.github.valerioisufi.drawview.model

import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.model.change.BoxPlacement
import io.github.valerioisufi.drawview.model.change.ChangeOrigin
import io.github.valerioisufi.drawview.model.change.CompositeChange
import io.github.valerioisufi.drawview.model.change.DocumentSession
import io.github.valerioisufi.drawview.model.change.ElementOrdering
import io.github.valerioisufi.drawview.model.change.Operation
import io.github.valerioisufi.drawview.model.change.OperationId
import io.github.valerioisufi.drawview.model.change.PlaceElements
import io.github.valerioisufi.drawview.model.change.ReorderElements
import io.github.valerioisufi.drawview.model.change.StackMove
import io.github.valerioisufi.drawview.model.change.UpdateTexts
import io.github.valerioisufi.drawview.model.change.snapshot
import io.github.valerioisufi.drawview.model.change.toData
import io.github.valerioisufi.drawview.platform.currentTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The moves of a selection in the stack of its page: past what it overlaps, as a block, undone,
 * kept apart from the moves on the page, the same on every device; the orders received are
 * checked.
 */
class StackMoveTest {

    /** A device: its session and the operations it made. */
    private class Device(val session: DocumentSession) {
        val sent = mutableListOf<Operation>()
        val document get() = session.document
        val page get() = document.pages[0]

        init {
            session.addObserver { if (it.origin != ChangeOrigin.REMOTE) sent += it.operation }
        }

        fun receiveFrom(other: Device) {
            for (operation in other.sent) session.receive(operation)
        }

        fun stack(): List<String> = page.stack.map { it.id }
    }

    private fun image(id: String, x: Float) = DrawImage(id).apply {
        this.x = x; y = 0f; width = 20f; height = 20f
    }

    /** A page of four images overlapping and a stroke far from them, added one on the other. */
    private fun device(replica: String = "ada"): Device {
        val device = Device(DocumentSession(DrawDocument(), replica = replica))
        for (element in listOf(image("a", 0f), image("e", 150f), image("b", 5f), image("c", 10f), image("d", 15f))) {
            device.session.submit(AddElements.of(device.page, listOf(element)))
        }
        return device
    }

    private fun Device.arrange(ids: Set<String>, move: StackMove) =
        session.submit(assertNotNull(ReorderElements.of(page, ids, move), "$move of $ids"))

    @Test
    fun forwardPassesTheNextElementItOverlaps() {
        val device = device()
        device.arrange(setOf("a"), StackMove.FORWARD)
        // Past e, far from it, and b
        assertEquals(listOf("e", "b", "a", "c", "d"), device.stack())
        device.arrange(setOf("a"), StackMove.FORWARD)
        assertEquals(listOf("e", "b", "c", "a", "d"), device.stack())
    }

    @Test
    fun backwardPassesTheNextElementItOverlaps() {
        val device = device()
        device.arrange(setOf("d"), StackMove.BACKWARD)
        assertEquals(listOf("a", "e", "b", "d", "c"), device.stack())
        device.arrange(setOf("b"), StackMove.BACKWARD)
        // Under a, past e
        assertEquals(listOf("b", "a", "e", "d", "c"), device.stack())
    }

    @Test
    fun frontAndBackGoPastEverything() {
        val device = device()
        device.arrange(setOf("a"), StackMove.FRONT)
        assertEquals(listOf("e", "b", "c", "d", "a"), device.stack())
        device.arrange(setOf("d"), StackMove.BACK)
        assertEquals(listOf("d", "e", "b", "c", "a"), device.stack())
    }

    @Test
    fun aSelectionMovesAsABlockKeepingItsOrder() {
        val device = device()
        device.arrange(setOf("a", "c"), StackMove.FRONT)
        assertEquals(listOf("e", "b", "d", "a", "c"), device.stack())
        device.arrange(setOf("a", "c"), StackMove.BACK)
        assertEquals(listOf("a", "c", "e", "b", "d"), device.stack())
        device.arrange(setOf("a", "c"), StackMove.FORWARD)
        assertEquals(listOf("e", "b", "a", "c", "d"), device.stack())
        device.arrange(setOf("c", "d"), StackMove.BACKWARD)
        assertEquals(listOf("e", "b", "c", "d", "a"), device.stack())
    }

    @Test
    fun aMoveThatWouldChangeNothingIsNone() {
        val device = device()
        val page = device.page
        assertNull(ReorderElements.of(page, setOf("d"), StackMove.FRONT))
        assertNull(ReorderElements.of(page, setOf("d"), StackMove.FORWARD))
        assertNull(ReorderElements.of(page, setOf("a"), StackMove.BACK))
        assertNull(ReorderElements.of(page, setOf("a"), StackMove.BACKWARD))
        // Above and under nothing it overlaps
        assertNull(ReorderElements.of(page, setOf("e"), StackMove.FRONT))
        assertNull(ReorderElements.of(page, setOf("e"), StackMove.BACK))
        assertNull(ReorderElements.of(page, setOf("x"), StackMove.FRONT))
    }

    @Test
    fun aMoveIsUndoneByThePreviousOrders() {
        val device = device()
        val before = device.stack()
        val undo = assertNotNull(device.arrange(setOf("a", "c"), StackMove.FRONT))
        device.session.submit(undo)
        assertEquals(before, device.stack())
    }

    @Test
    fun theSameMovesGiveTheSameStackOnEveryDevice() {
        val ada = device("ada")
        val bob = Device(DocumentSession(ada.document.snapshot().toDocument(), replica = "bob"))
        // Made apart: Ada sends a to the front, Bob brings b forward and sends d back
        ada.arrange(setOf("a"), StackMove.FRONT)
        bob.arrange(setOf("b"), StackMove.FORWARD)
        bob.arrange(setOf("d"), StackMove.BACK)

        ada.receiveFrom(bob)
        bob.receiveFrom(ada)
        assertEquals(ada.stack(), bob.stack())
        assertEquals(ada.document.snapshot(), bob.document.snapshot())
    }

    @Test
    fun theSameKeyMadeOnTwoDevicesIsStackedByIdentifier() {
        val ada = device("ada")
        val bob = Device(DocumentSession(ada.document.snapshot().toDocument(), replica = "bob"))
        val page = ada.page
        val between = ElementOrder.between(page.stack[2].order, page.stack[3].order)
        ada.session.submit(ReorderElements(page.id, listOf(ElementOrdering("d", between))))
        bob.session.submit(ReorderElements(page.id, listOf(ElementOrdering("a", between))))

        ada.receiveFrom(bob)
        bob.receiveFrom(ada)
        assertEquals(listOf("e", "b", "a", "d", "c"), ada.stack())
        assertEquals(ada.stack(), bob.stack())
    }

    @Test
    fun aMoveAndAReorderMadeApartBothStay() {
        val ada = device("ada")
        val bob = Device(DocumentSession(ada.document.snapshot().toDocument(), replica = "bob"))
        val image = ada.page.find("a") as DrawImage
        ada.session.submit(PlaceElements(ada.page.id, listOf(BoxPlacement("a", 40f, 40f, image.width, image.height, 0f))))
        bob.arrange(setOf("a"), StackMove.FRONT)

        ada.receiveFrom(bob)
        bob.receiveFrom(ada)
        for (device in listOf(ada, bob)) {
            assertEquals(40f, (device.page.find("a") as DrawImage).x)
            assertEquals("a", device.stack().last())
        }
    }

    @Test
    fun undoingAReorderLeavesTheMoveOfAnotherDevice() {
        val ada = device("ada")
        val bob = Device(DocumentSession(ada.document.snapshot().toDocument(), replica = "bob"))
        val undo = assertNotNull(ada.arrange(setOf("a"), StackMove.FRONT))
        bob.receiveFrom(ada)
        val image = bob.page.find("a") as DrawImage
        bob.session.submit(PlaceElements(bob.page.id, listOf(BoxPlacement("a", 40f, 40f, image.width, image.height, 0f))))
        ada.receiveFrom(bob)

        ada.session.submit(undo)
        assertEquals("a", ada.stack().first())
        assertEquals(40f, (ada.page.find("a") as DrawImage).x)
    }

    @Test
    fun anEditOfATextKeepsItsOrder() {
        val device = device()
        val text = DrawText("t").apply { x = 0f; y = 0f; width = 20f; height = 10f; this.text = "waves" }
        device.session.submit(AddElements.of(device.page, listOf(text)))
        device.arrange(setOf("t"), StackMove.BACK)
        device.session.submit(UpdateTexts(device.page.id, listOf(text.toData().copy(text = "particles", order = null))))
        assertEquals("t", device.stack().first())
    }

    @Test
    fun anElementMovedToAnotherPageKeepsItsOrderUnlessPutOnTop() {
        val device = device()
        val document = device.document
        document.addPage()
        val second = document.pages[1]
        device.session.submit(AddElements.of(second, listOf(image("f", 0f))))
        device.session.submit(PlaceElements(device.page.id, listOf(BoxPlacement("a", 0f, 0f, 20f, 20f, 0f)), second.id))
        // Older than f: under it
        assertEquals(listOf("a", "f"), second.stack.map { it.id })
        val undo = assertNotNull(
            device.session.submit(
                CompositeChange(
                    listOf(
                        PlaceElements(device.page.id, listOf(BoxPlacement("b", 0f, 0f, 20f, 20f, 0f)), second.id),
                        ReorderElements.onTop(second.id, listOf("b"))
                    )
                )
            )
        )
        assertEquals(listOf("a", "f", "b"), second.stack.map { it.id })
        device.session.submit(undo)
        assertEquals(listOf("a", "f"), second.stack.map { it.id })
        assertEquals(listOf("e", "b", "c", "d"), device.stack())
    }

    @Test
    fun theOrdersReceivedAreChecked() {
        val ada = device("ada")
        // After the stamps of Ada (made in the same millisecond, maybe)
        val now = Stamp(currentTimeMillis() + 1_000, 0, "bob")
        fun admitted(order: ElementOrder?) =
            ada.session.admits(Operation(OperationId("bob", 1), now, ReorderElements(ada.page.id, listOf(ElementOrdering("a", order)))))

        assertTrue(admitted(null))
        assertTrue(admitted(ElementOrder.between(ada.page.stack[0].order, ada.page.stack[1].order)))
        assertTrue(admitted(ElementOrder.onTop(now, 3)))
        // Above the next element put on top, after its stamp, malformed
        assertFalse(admitted(ElementOrder.parse("Z")))
        assertFalse(admitted(ElementOrder.onTop(Stamp(now.time + 1, 0, "bob"), 0)))
        assertFalse(admitted(ElementOrder.UNASSIGNED))
    }
}
