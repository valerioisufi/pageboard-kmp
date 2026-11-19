package io.github.valerioisufi.pageboard.model.change

import io.github.valerioisufi.pageboard.model.BrushConfig
import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.model.PageElement
import io.github.valerioisufi.pageboard.model.Page
import io.github.valerioisufi.pageboard.model.StrokeElement
import io.github.valerioisufi.pageboard.model.ElementOrder
import io.github.valerioisufi.pageboard.model.Stamp
import io.github.valerioisufi.pageboard.model.StrokeMesh
import io.github.valerioisufi.pageboard.model.StrokeTool
import io.github.valerioisufi.pageboard.platform.currentTimeMillis
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * The cost of the changes on a large document (see `docs/plans/performance.md`): the operations of
 * another device received, the strokes drawn here submitted.
 */
class ApplyBenchmark {

    /** 1,000 strokes of another device received on a document of 100 pages of 1,000 strokes. */
    @Test
    fun receivingOperations() {
        val document = largeDocument()
        val session = DocumentSession(document, replica = "here")
        val now = currentTimeMillis()
        val operations = List(OPERATIONS) { i ->
            val page = document.pages[i % document.pages.size]
            Operation(OperationId("there", i + 1L), Stamp(now + i, 0, "there"), AddElements.of(page, listOf(stroke())))
        }
        val time = measureTime {
            for (operation in operations) assertTrue(session.receive(operation))
        }
        report("M4 receiving $OPERATIONS operations on 100,000 elements", "$time (${time / OPERATIONS} each)")
    }

    /** Strokes drawn here, on a page of the same document. */
    @Test
    fun submittingStrokes() {
        val document = largeDocument()
        val session = DocumentSession(document, replica = "here")
        val page = document.pages.last()
        // Warm up
        repeat(OPERATIONS) { session.submit(AddElements.of(page, listOf(stroke()))) }
        val time = measureTime {
            repeat(OPERATIONS) { session.submit(AddElements.of(page, listOf(stroke()))) }
        }
        report("M4 submitting a stroke on 100,000 elements", "${time / OPERATIONS} each")
    }

    /** A page of 10,000 strokes built from the elements loaded (as the stores load a page). */
    @Test
    fun buildingAPage() {
        val elements = List(LOADED) { i -> stroke().also { it.order = ElementOrder.built(ElementOrder.layerOf(it), i) } }
        // Warm up
        repeat(2) { build(elements) }
        val times = List(5) { measureTime { build(elements) } }.sorted()
        report("M4 building a page of $LOADED strokes loaded", "${times[times.size / 2]}")
    }

    /** The stack of a page of 1,000 strokes read after every stroke, as the first tile drawn after it reads it. */
    @Test
    fun readingTheStackAfterAStroke() {
        val document = largeDocument()
        val session = DocumentSession(document, replica = "here")
        val page = document.pages.last()
        repeat(OPERATIONS) {
            session.submit(AddElements.of(page, listOf(stroke())))
            page.snapshotContent().paintOrder.size
        }
        var read = 0L
        repeat(OPERATIONS) {
            session.submit(AddElements.of(page, listOf(stroke())))
            read += measureTime { check(page.snapshotContent().paintOrder.isNotEmpty()) }.inWholeNanoseconds
        }
        report("M4 the paint order of a page of 2,000 strokes read after a stroke", "%.3f ms".format(read / 1e6 / OPERATIONS))
    }

    /** The page the stores build of [elements]. */
    private fun build(elements: List<PageElement>): Page = Page().apply { load(elements) }

    private fun largeDocument(): Document {
        val document = Document(addInitialPage = false)
        repeat(PAGES) {
            val page = document.addPage()
            repeat(STROKES_PER_PAGE) { page.add(stroke()) }
        }
        return document
    }

    /** A stroke of one triangle: what is measured is finding and adding it, not its mesh. */
    private fun stroke() = StrokeElement(
        mesh = StrokeMesh(
            positions = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f),
            indices = shortArrayOf(0, 1, 2),
            color = 0xFF000000.toInt(),
            tool = StrokeTool.PRESSURE_PEN,
            size = 1f
        ),
        brushConfig = BrushConfig(size = 1f)
    )

    private fun report(measure: String, value: String) = println("BENCHMARK $measure: $value")

    private companion object {
        const val PAGES = 100
        const val STROKES_PER_PAGE = 1_000
        const val OPERATIONS = 1_000
        const val LOADED = 10_000
    }
}
