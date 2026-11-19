package io.github.valerioisufi.drawview.canvas

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.valerioisufi.drawview.canvas.history.HistoryManager
import io.github.valerioisufi.drawview.model.BrushConfig
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawElement
import io.github.valerioisufi.drawview.model.DrawImage
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.DrawPdf
import io.github.valerioisufi.drawview.model.DrawStroke
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.DrawTextMarkup
import io.github.valerioisufi.drawview.model.ElementOrder
import io.github.valerioisufi.drawview.model.FractionalIndex
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.Stamp
import io.github.valerioisufi.drawview.model.StrokeMesh
import io.github.valerioisufi.drawview.model.StrokePoint
import io.github.valerioisufi.drawview.model.StrokeTool
import io.github.valerioisufi.drawview.model.TextMarkupKind
import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.model.change.AddPage
import io.github.valerioisufi.drawview.model.change.BoxPlacement
import io.github.valerioisufi.drawview.model.change.TextPlacement
import io.github.valerioisufi.drawview.model.change.ChangeOrigin
import io.github.valerioisufi.drawview.model.change.CompositeChange
import io.github.valerioisufi.drawview.model.change.DocumentChange
import io.github.valerioisufi.drawview.model.change.DocumentSession
import io.github.valerioisufi.drawview.model.change.ElementOrdering
import io.github.valerioisufi.drawview.model.change.HybridClock
import io.github.valerioisufi.drawview.model.change.MarkupStyle
import io.github.valerioisufi.drawview.model.change.MovePages
import io.github.valerioisufi.drawview.model.change.Operation
import io.github.valerioisufi.drawview.model.change.PageData
import io.github.valerioisufi.drawview.model.change.PlaceElements
import io.github.valerioisufi.drawview.model.change.RemoveElements
import io.github.valerioisufi.drawview.model.change.RemovePage
import io.github.valerioisufi.drawview.model.change.RenameDocument
import io.github.valerioisufi.drawview.model.change.ReorderElements
import io.github.valerioisufi.drawview.model.change.RestyleMarkups
import io.github.valerioisufi.drawview.model.change.SetDocumentDefaults
import io.github.valerioisufi.drawview.model.change.SetPageFormat
import io.github.valerioisufi.drawview.model.change.StackMove
import io.github.valerioisufi.drawview.model.change.StrokePlacement
import io.github.valerioisufi.drawview.model.change.UpdateTexts
import io.github.valerioisufi.drawview.model.change.toData
import io.github.valerioisufi.drawview.model.geometry.AffineTransform
import io.github.valerioisufi.drawview.model.geometry.Rect
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * Several devices changing the same document (undo and redo too) and exchanging their operations
 * in different orders end up with the same document.
 */
class ConvergenceTest {

    private fun stroke(x: Float, y: Float): DrawStroke {
        val mesh = StrokeMesh(
            positions = floatArrayOf(x, y, x + 5f, y, x + 5f, y + 5f, x, y + 5f),
            indices = shortArrayOf(0, 1, 2, 0, 2, 3),
            color = Color.Black.toArgb(),
            tool = StrokeTool.MARKER,
            size = 1f,
            points = listOf(StrokePoint(x, y, 0.5f, 0L), StrokePoint(x + 5f, y + 5f, 0.5f, 16L))
        )
        return DrawStroke(mesh = mesh, brushConfig = BrushConfig(tool = StrokeTool.MARKER, size = 1f))
    }

    private fun text(content: String) = DrawText().apply {
        text = content
        x = 10f; y = 20f; width = 40f; height = 10f
        fontSize = 16f
    }

    /** The content shared by the devices at the start. */
    private fun initialPages(): List<PageData> {
        val document = DrawDocument()
        document.addPage()
        document.addPage()
        val (first, second, third) = document.pages
        listOf(stroke(10f, 10f), stroke(30f, 10f), stroke(50f, 10f)).forEach { first.add(it) }
        first.add(text("one"))
        first.add(DrawImage().apply { resourceId = "img"; x = 30f; y = 40f; width = 20f; height = 15f })
        second.add(DrawPdf(pdfPageIndex = 0).apply { resourceId = "pdf" })
        second.add(
            DrawTextMarkup(
                resourceId = "pdf", pdfPageIndex = 0, pdfWidthPt = 595f, pdfHeightPt = 842f, startIndex = 0, endIndex = 4,
                text = "Hash", rects = listOf(Rect(72f, 100f, 120f, 124f)), kind = TextMarkupKind.HIGHLIGHT, color = 1
            )
        )
        third.add(text("three"))
        return document.pages.map { it.toData() }
    }

    /** A device: its copy of the document, its history and the operations it made. */
    private class Device(val name: String, pages: List<PageData>) {
        val document = DrawDocument(id = "document", addInitialPage = false).also { document ->
            pages.forEach { document.appendPage(it.toPage(document)) }
            document.reindexPages()
        }
        val session = DocumentSession(document, name)
        val history = HistoryManager(session, maxHistorySize = 1000)

        /** The operations made here, with the version vector they were made after. */
        val made = mutableListOf<Pair<Operation, Map<String, Long>>>()

        /** Every operation applied here, in order (to explain a difference). */
        val applied = mutableListOf<Operation>()

        init {
            session.addObserver { applied.add(it.operation) }
            session.addObserver { committed ->
                if (committed.origin != ChangeOrigin.REMOTE) {
                    val after = session.versionVector - name
                    made.add(Json.decodeFromString(Operation.serializer(), Json.encodeToString(Operation.serializer(), committed.operation)) to after)
                }
            }
        }

        /**
         * Applies [operation], made on another device: admitted, as every operation the editor and
         * the history make (see [DocumentSession.admits]).
         */
        fun receive(operation: Operation): Boolean {
            assertTrue(session.admits(operation), "not admitted: ${operation.id} ${operation.stamp} ${operation.change}")
            return session.receive(operation)
        }

        /** Whether [operation] (made after [dependencies]) can be applied here now. */
        fun canReceive(operation: Operation, dependencies: Map<String, Long>): Boolean {
            val vector = session.versionVector
            if (operation.id.sequence != (vector[operation.id.replica] ?: 0L) + 1) return false
            return dependencies.all { (replica, sequence) -> (vector[replica] ?: 0L) >= sequence }
        }
    }

    @Test
    fun devicesExchangingOperationsInAnyCausalOrderConverge() {
        repeat(30) { seed -> converge(Random(seed)) }
    }

    private fun converge(random: Random) {
        val pages = initialPages()
        val devices = listOf("ada", "bob", "cy").map { Device(it, pages) }

        repeat(150) {
            val device = devices.random(random)
            if (random.nextInt(3) == 0) {
                // Receives an operation of another device, when it can
                val candidates = devices.filter { it !== device }.flatMap { it.made }.filter { (operation, dependencies) ->
                    device.canReceive(operation, dependencies)
                }
                candidates.randomOrNull(random)?.let { (operation) -> device.receive(operation) }
            } else {
                when (random.nextInt(10)) {
                    0 -> device.history.undo()
                    1 -> device.history.redo()
                    else -> randomChange(device.document, random)?.let { device.session.submit(it) }
                }
            }
        }

        // Every device ends up receiving every operation
        exchange(devices)

        val expected = snapshot(devices[0].document)
        for (device in devices.drop(1)) {
            assertEquals(devices[0].session.versionVector, device.session.versionVector)
            val actual = snapshot(device.document)
            val line = expected.indices.firstOrNull { expected[it] != actual.getOrNull(it) } ?: continue
            val ids = Regex("id=([0-9a-f-]{36})").findAll(expected[line] + " " + actual.getOrNull(line)).map { it.groupValues[1] }.toSet()
            val trace = devices.joinToString("\n\n") { d ->
                d.name + " applied:\n" + d.applied.withIndex()
                    .filter { (_, op) -> ids.any { id -> Json.encodeToString(Operation.serializer(), op).contains(id) } }
                    .joinToString("\n") { (i, op) -> "  #$i ${op.id.replica}/${op.id.sequence} ${op.stamp} ${op.change}" }
            }
            println("TRACE ids=$ids\n$trace")
            throw AssertionError(
                "${device.name} differs at line $line\nexpected: ${expected[line]}\nactual:   ${actual.getOrNull(line)}\n\n" +
                    "${devices[0].name}:\n${expected.joinToString("\n")}\n\n${device.name}:\n${actual.joinToString("\n")}"
            )
        }
    }

    /** Gives each one of [devices] the operations the others made (and the ones they make meanwhile). */
    private fun exchange(devices: List<Device>) {
        var delivered: Boolean
        do {
            delivered = false
            for (device in devices) {
                for ((operation, dependencies) in devices.filter { it !== device }.flatMap { it.made }) {
                    if (device.canReceive(operation, dependencies)) {
                        assertTrue(device.receive(operation))
                        delivered = true
                    }
                }
            }
        } while (delivered)
    }

    /**
     * The content of [document] with the stamps of its pages and elements, one line each: the
     * pages shown in their order, then the removed ones (hidden), by identifier.
     */
    private fun snapshot(document: DrawDocument): List<String> =
        listOf(
            "defaults ${document.defaultWidthMm} ${document.defaultBackground} ${document.defaultsVersion}",
            "title ${document.title} ${document.titleVersion}"
        ) +
            (document.pages + document.removedPages.values.sortedBy { it.id }).flatMap { page ->
                val data = page.toData()
                listOf(
                    "page ${data.copy(elements = emptyList())} ${page.added} ${page.removed} ${page.orderVersion} ${page.formatVersion} " +
                        "${page.contentVersions} ${page.removalSaw}"
                ) +
                    page.elements().map { "  ${element(it)}" } +
                    page.removedElements.sortedBy { it.id }.map { "  hidden ${element(it)}" }
            }

    private fun element(element: DrawElement) =
        "${element.toData()} ${element.added} ${element.removed} ${element.placed} ${element.ordered} ${element.updated}"

    private fun randomChange(document: DrawDocument, random: Random): DocumentChange? {
        if (document.pages.isEmpty()) return AddPage.of(DrawPage(orderKey = document.orderKeyAt(0)))
        val page = document.pages[random.nextInt(document.pages.size)]
        val elements = page.elements()
        fun someElements(): List<DrawElement> = elements.shuffled(random).take(random.nextInt(1, 4))
        return when (random.nextInt(14)) {
            0, 1 -> AddElements.of(page, List(random.nextInt(1, 3)) { stroke(random.nextFloat() * 100f, random.nextFloat() * 100f) } + text("t${random.nextInt(100)}"))
            2 -> if (elements.isEmpty()) null else RemoveElements.of(page, someElements())
            3 -> {
                val placements = someElements().mapNotNull { element ->
                    when (element) {
                        is DrawStroke -> StrokePlacement(element.id, AffineTransform(tx = random.nextInt(10).toFloat(), ty = random.nextInt(10).toFloat()))
                        is DrawText -> TextPlacement(element.id, random.nextInt(50).toFloat(), random.nextInt(50).toFloat(), 30f, 0f, 10f + random.nextInt(10), autoWidth = false)
                        is DrawImage -> BoxPlacement(element.id, random.nextInt(50).toFloat(), random.nextInt(50).toFloat(), 30f, 10f, 0f)
                        else -> null
                    }
                }
                val target = document.pages[random.nextInt(document.pages.size)]
                val move = PlaceElements(page.id, placements, target.id)
                when {
                    placements.isEmpty() -> null
                    // On top of the page they go to, as the editor moves them
                    target !== page && random.nextBoolean() -> CompositeChange(listOf(move, ReorderElements.onTop(target.id, placements.map { it.id })))
                    else -> move
                }
            }
            4 -> page.texts.firstOrNull()?.let { UpdateTexts(page.id, listOf(it.toData().copy(text = "u${random.nextInt(100)}"))) }
            5 -> AddPage.of(DrawPage(orderKey = document.orderKeyAt(random.nextInt(document.pages.size + 1))).apply { add(stroke(5f, 5f)) })
            6 -> if (document.pages.size > 1) RemovePage(page.id) else null
            7 -> MovePages.toOrder(document, document.pages.map { it.id }.shuffled(random))
            8 -> SetPageFormat(page.id, 100f + random.nextInt(100), 100f + random.nextInt(100), if (random.nextBoolean()) PageBackground.Ruled() else null)
            9 -> SetDocumentDefaults(150f + random.nextInt(100), 200f, if (random.nextBoolean()) PageBackground.Grid() else PageBackground.Solid())
            10 -> RenameDocument("Notes ${random.nextInt(100)}")
            11 -> ReorderElements.of(page, someElements().map { it.id }, StackMove.entries.random(random))
            12 -> {
                // An order between two of the stack, made apart on several devices
                val stack = page.stack
                if (stack.size < 2) null else {
                    val at = random.nextInt(1, stack.size)
                    val (below, above) = stack[at - 1].order to stack[at].order
                    if (below >= above) null else ReorderElements(page.id, listOf(ElementOrdering(stack.random(random).id, ElementOrder.between(below, above))))
                }
            }
            else -> page.markups.firstOrNull()?.let {
                RestyleMarkups(page.id, listOf(MarkupStyle(it.id, TextMarkupKind.entries.random(random), random.nextInt(10))))
            }
        }
    }

    // --- Made apart ---

    /** Writes [content] in the text of the first page. */
    private fun Device.write(content: String) {
        val page = document.pages[0]
        session.submit(UpdateTexts(page.id, listOf(page.texts.first().toData().copy(text = content))))
    }

    @Test
    fun aTextChangedApartKeepsTheContentLostAsACopy() {
        val devices = initialPages().let { pages -> listOf("ada", "bob").map { Device(it, pages) } }
        val (ada, bob) = devices
        ada.write("Ada's")
        bob.write("Bob's")
        exchange(devices)

        assertEquals(snapshot(ada.document), snapshot(bob.document))
        // The most recent content (Bob's), the other one in a copy under it
        val (text, copy) = ada.document.pages[0].texts.toList()
        assertEquals("Bob's", text.text)
        assertEquals("Ada's", copy.text)
        assertEquals(text.y + text.height + 4f, copy.y)
    }

    @Test
    fun aTextChangedAfterSeeingTheOtherChangeHasNoCopy() {
        val devices = initialPages().let { pages -> listOf("ada", "bob").map { Device(it, pages) } }
        val (ada, bob) = devices
        ada.write("first")
        exchange(devices)
        bob.write("second")
        exchange(devices)
        assertEquals(listOf("second"), ada.document.pages[0].texts.map { it.text })
        assertEquals(snapshot(ada.document), snapshot(bob.document))
    }

    @Test
    fun aPageRemovedWhileAnotherDeviceAddsToItStays() {
        val devices = initialPages().let { pages -> listOf("ada", "bob").map { Device(it, pages) } }
        val (ada, bob) = devices
        val pageId = ada.document.pages[2].id
        ada.session.submit(RemovePage(pageId))
        bob.session.submit(AddElements.of(bob.document.pages[2], listOf(stroke(60f, 60f))))
        exchange(devices)

        // On both, with what Bob added
        for (device in devices) assertEquals(1, device.document.pageById(pageId)?.strokes?.size, device.name)
        assertEquals(snapshot(ada.document), snapshot(bob.document))
        // Removed again, now that it was seen: gone
        ada.session.submit(RemovePage(pageId))
        exchange(devices)
        for (device in devices) assertNull(device.document.pageById(pageId), device.name)
        assertEquals(snapshot(ada.document), snapshot(bob.document))
    }

    @Test
    fun aPageRemovedWhileAnotherDeviceErasesFromItIsRemoved() {
        val devices = initialPages().let { pages -> listOf("ada", "bob").map { Device(it, pages) } }
        val (ada, bob) = devices
        val pageId = ada.document.pages[2].id
        ada.session.submit(RemovePage(pageId))
        bob.session.submit(RemoveElements.of(bob.document.pages[2], bob.document.pages[2].texts.toList()))
        exchange(devices)
        for (device in devices) assertNull(device.document.pageById(pageId), device.name)
        assertEquals(snapshot(ada.document), snapshot(bob.document))
    }

    @Test
    fun theCopiesOfAContentLostKeepNoRemovedPage() {
        val devices = initialPages().let { pages -> listOf("ada", "bob", "cy").map { Device(it, pages) } }
        val (ada, bob, cy) = devices
        fun Device.writeOnThirdPage(content: String) {
            val page = document.pages[2]
            session.submit(UpdateTexts(page.id, listOf(page.texts.first().toData().copy(text = content))))
        }
        bob.writeOnThirdPage("Bob's")
        cy.writeOnThirdPage("Cy's")
        // Ada sees both (keeping the one lost as a copy), then removes the page
        exchange(listOf(ada, bob))
        exchange(listOf(ada, cy))
        val pageId = ada.document.pages[2].id
        ada.session.submit(RemovePage(pageId))
        // Bob and Cy make the same copy later: the page stays removed
        exchange(devices)
        for (device in devices) assertNull(device.document.pageById(pageId), device.name)
        assertEquals(snapshot(ada.document), snapshot(bob.document))
        assertEquals(snapshot(ada.document), snapshot(cy.document))
    }

    // --- The pieces ---

    @Test
    fun fractionalKeysAreAlwaysBetweenTheirBounds() {
        val random = Random(7)
        val keys = mutableListOf(FractionalIndex.between(null, null))
        repeat(500) {
            val i = random.nextInt(keys.size + 1)
            val key = FractionalIndex.between(keys.getOrNull(i - 1), keys.getOrNull(i))
            keys.add(i, key)
        }
        assertEquals(keys.sorted(), keys)
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.none { it.endsWith("0") })
    }

    @Test
    fun reorderingMovesTheFewestKeys() {
        val keys = FractionalIndex.after(null, 6)
        // The last key goes first: only it gets a new key
        val order = listOf(keys[5]) + keys.take(5)
        val moved = FractionalIndex.reorder(order)
        assertEquals(setOf(0), moved.keys)
        val result = order.mapIndexed { i, key -> moved[i] ?: key }
        assertEquals(result.sorted(), result)
    }

    @Test
    fun theClockStaysAfterWhatItSees() {
        var now = 1_000L
        val clock = HybridClock("a") { now }
        val first = clock.next()
        assertTrue(clock.next() > first, "the same millisecond")
        // A device whose clock is ahead
        val remote = Stamp(5_000L, 3, "b")
        clock.observe(remote)
        val next = clock.next()
        assertTrue(next > remote)
        now = 2_000L
        assertTrue(clock.next() > next, "the wall clock behind the stamps seen")
        assertEquals(remote, Stamp.parse(remote.toString()))
        assertTrue(Stamp(10, 0, "a").toString() < Stamp(16, 0, "a").toString(), "the text order is the stamp order")
    }
}
