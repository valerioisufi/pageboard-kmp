package io.github.valerioisufi.drawview.model

import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.model.change.DocumentSession
import io.github.valerioisufi.drawview.model.change.Operation
import io.github.valerioisufi.drawview.model.change.OperationId
import io.github.valerioisufi.drawview.model.change.RenameDocument
import io.github.valerioisufi.drawview.model.change.UpdateTexts
import io.github.valerioisufi.drawview.model.change.toData
import io.github.valerioisufi.drawview.platform.currentTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The stamps of the operations of the other devices: a member with an application of its own
 * stamps its operations as it likes, and its signature still checks. Whether such an operation is
 * refused or applied, the changes made here after it win, and the clock here follows the wall
 * clock.
 */
class FutureStampTest {

    @Test
    fun anOperationStampedAtTheEndOfTimeDoesNotWinForGood() {
        val document = DrawDocument(title = "Physics")
        val session = DocumentSession(document, replica = "ada")
        // The greatest stamp there is
        runCatching { session.receive(Operation(OperationId("bob", 1), Stamp(Long.MAX_VALUE, Int.MAX_VALUE, "bob"), RenameDocument("Forever"))) }

        session.submit(RenameDocument("Optics"))
        assertEquals("Optics", document.title)
    }

    @Test
    fun theGreatestCounterALittleAheadDoesNotWinOverTheChangesMadeAfterIt() {
        val document = DrawDocument(title = "Physics")
        val session = DocumentSession(document, replica = "ada")
        // A minute ahead (within any skew of the clocks), with the greatest counter
        runCatching { session.receive(Operation(OperationId("bob", 1), Stamp(currentTimeMillis() + MINUTE, Int.MAX_VALUE, "bob"), RenameDocument("Mine"))) }

        session.submit(RenameDocument("Optics"))
        assertEquals("Optics", document.title)
    }

    @Test
    fun anOperationStampedFarAheadDoesNotPushTheClock() {
        val session = DocumentSession(DrawDocument(), replica = "ada")
        runCatching { session.receive(Operation(OperationId("bob", 1), Stamp(currentTimeMillis() + CENTURY, 0, "bob"), RenameDocument("Ahead"))) }

        assertTrue(session.clock.next().time < currentTimeMillis() + DAY)
    }

    @Test
    fun aTextAMemberSaysItSawTheFutureOfStaysEditable() {
        val document = DrawDocument()
        val session = DocumentSession(document, replica = "ada")
        val page = document.pages[0]
        val text = DrawText().apply {
            text = "waves"
            x = 10f; y = 20f; width = 40f; height = 10f
        }
        session.submit(AddElements.of(page, listOf(text)))
        // Bob changes it, saying its change included the ones of Ada of a century from now
        val forged = text.toData().copy(text = "particles", versions = Versions(mapOf("ada" to Stamp(currentTimeMillis() + CENTURY, 0, "ada"))))
        runCatching { session.receive(Operation(OperationId("bob", 1), Stamp(currentTimeMillis(), 0, "bob"), UpdateTexts(page.id, listOf(forged)))) }

        session.submit(UpdateTexts(page.id, listOf(text.toData().copy(text = "fields"))))
        assertEquals("fields", text.text)
    }

    private companion object {
        const val MINUTE = 60L * 1000
        const val DAY = 24 * 60 * MINUTE
        const val CENTURY = 100 * 365 * DAY
    }
}
