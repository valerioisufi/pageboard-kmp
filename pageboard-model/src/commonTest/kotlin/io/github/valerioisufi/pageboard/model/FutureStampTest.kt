package io.github.valerioisufi.pageboard.model

import io.github.valerioisufi.pageboard.model.change.AddElements
import io.github.valerioisufi.pageboard.model.change.AddPage
import io.github.valerioisufi.pageboard.model.change.TextPlacement
import io.github.valerioisufi.pageboard.model.change.CompositeChange
import io.github.valerioisufi.pageboard.model.change.DocumentChange
import io.github.valerioisufi.pageboard.model.change.DocumentSession
import io.github.valerioisufi.pageboard.model.change.HybridClock
import io.github.valerioisufi.pageboard.model.change.Operation
import io.github.valerioisufi.pageboard.model.change.OperationId
import io.github.valerioisufi.pageboard.model.change.PageData
import io.github.valerioisufi.pageboard.model.change.PlaceElements
import io.github.valerioisufi.pageboard.model.change.RemovePage
import io.github.valerioisufi.pageboard.model.change.RenameDocument
import io.github.valerioisufi.pageboard.model.change.TextData
import io.github.valerioisufi.pageboard.model.change.UpdateTexts
import io.github.valerioisufi.pageboard.model.change.toData
import io.github.valerioisufi.pageboard.platform.currentTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The stamps of the operations of the other devices: a member with an application of its own
 * stamps its operations as it likes, and its signature still checks. An operation stamped more
 * than an hour ahead of the wall clock here, by another replica, or carrying stamps after its own
 * is refused; the changes made here after an operation win over it, and the clock here follows
 * the wall clock.
 */
class FutureStampTest {

    private fun text(content: String) = TextElement().apply {
        text = content
        x = 10f; y = 20f; width = 40f; height = 10f
    }

    @Test
    fun anOperationStampedAtTheEndOfTimeDoesNotWinForGood() {
        val document = Document(title = "Physics")
        val session = DocumentSession(document, replica = "ada")
        // The greatest stamp there is
        runCatching { session.receive(Operation(OperationId("bob", 1), Stamp(Long.MAX_VALUE, Int.MAX_VALUE, "bob"), RenameDocument("Forever"))) }

        session.submit(RenameDocument("Optics"))
        assertEquals("Optics", document.title)
    }

    @Test
    fun theGreatestCounterALittleAheadDoesNotWinOverTheChangesMadeAfterIt() {
        val document = Document(title = "Physics")
        val session = DocumentSession(document, replica = "ada")
        // A minute ahead (within any skew of the clocks), with the greatest counter
        runCatching { session.receive(Operation(OperationId("bob", 1), Stamp(currentTimeMillis() + MINUTE, Int.MAX_VALUE, "bob"), RenameDocument("Mine"))) }

        session.submit(RenameDocument("Optics"))
        assertEquals("Optics", document.title)
    }

    @Test
    fun anOperationStampedFarAheadDoesNotPushTheClock() {
        val session = DocumentSession(Document(), replica = "ada")
        runCatching { session.receive(Operation(OperationId("bob", 1), Stamp(currentTimeMillis() + CENTURY, 0, "bob"), RenameDocument("Ahead"))) }

        assertTrue(session.clock.next().time < currentTimeMillis() + DAY)
    }

    @Test
    fun aTextAMemberSaysItSawTheFutureOfStaysEditable() {
        val document = Document()
        val session = DocumentSession(document, replica = "ada")
        val page = document.pages[0]
        val text = text("waves")
        session.submit(AddElements.of(page, listOf(text)))
        // Bob changes it, saying its change included the ones of Ada of a century from now
        val forged = text.toData().copy(text = "particles", versions = Versions(mapOf("ada" to Stamp(currentTimeMillis() + CENTURY, 0, "ada"))))
        runCatching { session.receive(Operation(OperationId("bob", 1), Stamp(currentTimeMillis(), 0, "bob"), UpdateTexts(page.id, listOf(forged)))) }

        session.submit(UpdateTexts(page.id, listOf(text.toData().copy(text = "fields"))))
        assertEquals("fields", text.text)
    }

    @Test
    fun aStampIsAdmittedUpToAnHourAheadOfTheWallClock() {
        val now = 1_800_000_000_000L
        val clock = HybridClock("ada") { now }
        assertTrue(clock.admits(Stamp(0, 0, "bob")))
        assertTrue(clock.admits(Stamp(now - DAY, 7, "bob")))
        assertTrue(clock.admits(Stamp(now + HybridClock.MAX_AHEAD_MS, Int.MAX_VALUE, "bob")))
        assertFalse(clock.admits(Stamp(now + HybridClock.MAX_AHEAD_MS + 1, 0, "bob")))
        assertFalse(clock.admits(Stamp(Long.MAX_VALUE, 0, "bob")))
        // No negative part: out of the text order of the stores
        assertFalse(clock.admits(Stamp(-1, 0, "bob")))
        assertFalse(clock.admits(Stamp(now, -1, "bob")))
    }

    @Test
    fun theCounterAtItsGreatestMovesTheClockToTheNextMillisecond() {
        val clock = HybridClock("ada") { 1_000L }
        val seen = Stamp(5_000L, Int.MAX_VALUE, "bob")
        clock.observe(seen)

        val next = clock.next()
        assertEquals(Stamp(5_001L, 0, "ada"), next)
        assertTrue(next > seen)
        assertEquals(Stamp(5_001L, 1, "ada"), clock.next())
    }

    @Test
    fun anOperationIsReceivedWithinTheHourOnly() {
        val document = Document(title = "Physics")
        val session = DocumentSession(document, replica = "ada")
        val beyond = Stamp(currentTimeMillis() + HybridClock.MAX_AHEAD_MS + MINUTE, 0, "bob")
        assertFailsWith<IllegalArgumentException> { session.receive(Operation(OperationId("bob", 1), beyond, RenameDocument("Ahead"))) }
        assertEquals("Physics", document.title)
        assertEquals(0L, session.lastApplied("bob"))

        // A clock ahead within the hour: taken, and the changes made here after it win
        val within = Stamp(currentTimeMillis() + HybridClock.MAX_AHEAD_MS - MINUTE, 0, "bob")
        assertTrue(session.receive(Operation(OperationId("bob", 1), within, RenameDocument("Within"))))
        assertEquals("Within", document.title)
        session.submit(RenameDocument("Optics"))
        assertEquals("Optics", document.title)
    }

    @Test
    fun anOperationIsReceivedStampedByItsReplicaOnly() {
        val document = Document(title = "Physics")
        val session = DocumentSession(document, replica = "ada")
        val operation = Operation(OperationId("bob", 1), Stamp(currentTimeMillis(), 0, "cy"), RenameDocument("As Cy"))

        assertFalse(session.admits(operation))
        assertFailsWith<IllegalArgumentException> { session.receive(operation) }
        assertEquals("Physics", document.title)
        assertEquals(0L, session.lastApplied("bob"))
    }

    @Test
    fun anOperationCarryingAStampAfterItsOwnIsRefused() {
        val document = Document()
        val session = DocumentSession(document, replica = "ada")
        val page = document.pages[0]
        val text = text("waves")
        session.submit(AddElements.of(page, listOf(text)))
        val stamp = Stamp(currentTimeMillis(), 0, "bob")

        // The changes carrying [carried]: an order, the versions of a text, what a removal saw
        fun changes(carried: Stamp): List<DocumentChange> {
            val order = ElementOrder.onTop(carried, 0)
            val added = TextData("added", "x", x = 0f, y = 0f, width = 10f, color = 0, fontSize = 16f, order = order)
            val placed = PlaceElements(page.id, listOf(TextPlacement(text.id, 1f, 2f, 40f, 0f, 16f, autoWidth = false, order = order)))
            return listOf(
                AddElements(page.id, listOf(added)),
                placed,
                UpdateTexts(page.id, listOf(text.toData().copy(text = "particles", versions = Versions(mapOf("cy" to carried))))),
                RemovePage(page.id, saw = Versions(mapOf("cy" to carried))),
                AddPage(PageData("second", document.orderKeyAt(1), 210f, 297f, elements = listOf(added))),
                CompositeChange(listOf(RenameDocument("Lab"), CompositeChange(listOf(placed))))
            )
        }

        for (change in changes(Stamp(stamp.time + 1, 0, "cy"))) {
            val operation = Operation(OperationId("bob", 1), stamp, change)
            assertFalse(session.admits(operation), "$change")
            assertFailsWith<IllegalArgumentException> { session.receive(operation) }
        }
        assertEquals(0L, session.lastApplied("bob"))
        assertEquals("waves", text.text)

        // What its author had seen: up to its own stamp
        for (change in changes(stamp) + changes(Stamp(stamp.time - DAY, 3, "cy"))) {
            assertTrue(session.admits(Operation(OperationId("bob", 1), stamp, change)), "$change")
        }
    }

    @Test
    fun aDocumentLoadedWithStampsAheadOfTheWallClockKeepsItsChangesAfterThem() {
        // Stored by this device, whose clock went back since (or received from a clock ahead)
        val ahead = Stamp(currentTimeMillis() + 2 * DAY, 0, "bob")
        val document = Document(title = "Physics")
        val text = text("waves")
        document.pages[0].add(text)
        text.placed = ahead
        document.titleVersion = Stamp(ahead.time + 1, 0, "bob")
        val session = DocumentSession(document, replica = "ada")

        session.submit(RenameDocument("Optics"))
        session.submit(PlaceElements(document.pages[0].id, listOf(TextPlacement(text.id, 50f, 60f, 40f, 0f, text.fontSize, autoWidth = false))))
        assertEquals("Optics", document.title)
        assertEquals(50f, text.x)
    }

    private companion object {
        const val MINUTE = 60L * 1000
        const val DAY = 24 * 60 * MINUTE
        const val CENTURY = 100 * 365 * DAY
    }
}
