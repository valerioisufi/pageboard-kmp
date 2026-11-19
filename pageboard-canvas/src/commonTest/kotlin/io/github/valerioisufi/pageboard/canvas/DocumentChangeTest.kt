package io.github.valerioisufi.pageboard.canvas

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.valerioisufi.pageboard.canvas.history.HistoryManager
import io.github.valerioisufi.pageboard.model.BrushConfig
import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.model.PageElement
import io.github.valerioisufi.pageboard.model.ImageElement
import io.github.valerioisufi.pageboard.model.Page
import io.github.valerioisufi.pageboard.model.PdfElement
import io.github.valerioisufi.pageboard.model.DocumentResource
import io.github.valerioisufi.pageboard.model.StrokeElement
import io.github.valerioisufi.pageboard.model.TextElement
import io.github.valerioisufi.pageboard.model.TextMarkupElement
import io.github.valerioisufi.pageboard.model.PageBackground
import io.github.valerioisufi.pageboard.model.StrokeMesh
import io.github.valerioisufi.pageboard.model.StrokePoint
import io.github.valerioisufi.pageboard.model.StrokeTool
import io.github.valerioisufi.pageboard.model.TextMarkupKind
import io.github.valerioisufi.pageboard.model.Versions
import io.github.valerioisufi.pageboard.model.change.AddElements
import io.github.valerioisufi.pageboard.model.change.AddPage
import io.github.valerioisufi.pageboard.model.change.AddResource
import io.github.valerioisufi.pageboard.model.change.BoxPlacement
import io.github.valerioisufi.pageboard.model.change.TextPlacement
import io.github.valerioisufi.pageboard.model.change.ChangeOrigin
import io.github.valerioisufi.pageboard.model.change.ChangePersister
import io.github.valerioisufi.pageboard.model.change.CompositeChange
import io.github.valerioisufi.pageboard.model.change.DocumentChange
import io.github.valerioisufi.pageboard.model.change.DocumentPersistence
import io.github.valerioisufi.pageboard.model.change.DocumentSession
import io.github.valerioisufi.pageboard.model.change.MarkupStyle
import io.github.valerioisufi.pageboard.model.change.MovePages
import io.github.valerioisufi.pageboard.model.change.Operation
import io.github.valerioisufi.pageboard.model.change.PageMove
import io.github.valerioisufi.pageboard.model.change.PlaceElements
import io.github.valerioisufi.pageboard.model.change.RemoveElements
import io.github.valerioisufi.pageboard.model.change.RemovePage
import io.github.valerioisufi.pageboard.model.change.RestyleMarkups
import io.github.valerioisufi.pageboard.model.change.SetDocumentDefaults
import io.github.valerioisufi.pageboard.model.change.SetPageFormat
import io.github.valerioisufi.pageboard.model.change.StrokePlacement
import io.github.valerioisufi.pageboard.model.change.TextData
import io.github.valerioisufi.pageboard.model.change.UpdateTexts
import io.github.valerioisufi.pageboard.model.change.toData
import io.github.valerioisufi.pageboard.model.geometry.AffineTransform
import io.github.valerioisufi.pageboard.model.geometry.Rect
import io.github.valerioisufi.pageboard.model.newElementId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class DocumentChangeTest {

    private fun stroke(x: Float, y: Float): StrokeElement {
        val mesh = StrokeMesh(
            positions = floatArrayOf(x, y, x + 5f, y, x + 5f, y + 5f, x, y + 5f),
            indices = shortArrayOf(0, 1, 2, 0, 2, 3),
            color = Color.Black.toArgb(),
            tool = StrokeTool.MARKER,
            size = 1f,
            points = listOf(StrokePoint(x, y, 0.5f, 0L), StrokePoint(x + 5f, y + 5f, 0.5f, 16L))
        )
        return StrokeElement(mesh = mesh, brushConfig = BrushConfig(tool = StrokeTool.MARKER, size = 1f))
    }

    private fun text(content: String, x: Float = 10f) = TextElement().apply {
        text = content
        this.x = x; y = 20f; width = 40f; height = 10f
        fontSize = 16f
    }

    private fun image(x: Float = 30f) = ImageElement().apply {
        resourceId = "img"
        this.x = x; y = 40f; width = 20f; height = 15f
    }

    private fun markup() = TextMarkupElement(
        resourceId = "pdf", pdfPageIndex = 0, pdfWidthPt = 595f, pdfHeightPt = 842f,
        startIndex = 0, endIndex = 4, text = "Hash", rects = listOf(Rect(72f, 100f, 120f, 124f)),
        kind = TextMarkupKind.HIGHLIGHT, color = 0xFFFFF176.toInt()
    )

    /**
     * The whole content of [this] as values, one line per page and per element (not the versions
     * of the texts, like the stamps).
     */
    private fun Document.snapshot(): List<String> =
        listOf("defaults $defaultWidthMm x $defaultHeightMm $defaultBackground", "resources ${resources.map { it.id }}") +
            pages.flatMap { page ->
                val data = page.toData()
                listOf("page ${page.index} ${data.copy(elements = emptyList())}") +
                    data.elements.map { "  ${(it as? TextData)?.copy(versions = Versions.NONE) ?: it}" }
            }

    /** Fails on the first line that differs. */
    private fun assertSameContent(expected: List<String>, actual: List<String>, message: String = "") {
        val line = (0 until maxOf(expected.size, actual.size)).firstOrNull { expected.getOrNull(it) != actual.getOrNull(it) } ?: return
        throw AssertionError("$message: line $line\nexpected: ${expected.getOrNull(line)}\nactual:   ${actual.getOrNull(line)}")
    }

    private fun documentWithContent(): Document {
        val document = Document()
        document.addPage()
        document.addPage()
        val (first, second) = document.pages
        listOf(stroke(10f, 10f), stroke(30f, 10f), stroke(50f, 10f)).forEach { first.add(it) }
        first.add(text("one"))
        first.add(text("two", 60f))
        first.add(image())
        second.add(PdfElement(pdfPageIndex = 0).apply { resourceId = "pdf" })
        second.add(markup())
        second.add(stroke(10f, 80f))
        return document
    }

    /**
     * Applies [change] ([check] runs after it), its inverse (undo), the inverse of the inverse
     * (redo) and undo again: the document is left as it was.
     */
    private fun assertUndoable(document: Document, change: DocumentChange, check: () -> Unit = {}) {
        val session = DocumentSession(document)
        val before = document.snapshot()
        val undo = assertNotNull(session.submit(change), "$change changed nothing")
        val after = document.snapshot()
        check()
        val redo = assertNotNull(session.submit(undo, ChangeOrigin.UNDO))
        assertSameContent(before, document.snapshot(), "undo of $change")
        val undoAgain = assertNotNull(session.submit(redo, ChangeOrigin.REDO))
        assertSameContent(after, document.snapshot(), "redo of $change")
        assertNotNull(session.submit(undoAgain, ChangeOrigin.UNDO))
        assertSameContent(before, document.snapshot(), "second undo of $change")
    }

    // --- Every change is undone exactly ---

    @Test
    fun elementsAreAddedAndRemoved() {
        val document = documentWithContent()
        val page = document.pages[0]
        assertUndoable(document, AddElements.of(page, listOf(stroke(0f, 0f), text("new"), image(70f)))) {
            assertEquals(4, page.strokes.size)
        }
        // Removed from the middle, they go back where they were
        val strokes = page.strokes.toList()
        assertUndoable(document, RemoveElements.of(page, listOf(strokes[2], strokes[0], page.texts[0]))) {
            assertEquals(listOf(strokes[1]), page.strokes.toList())
        }
        assertEquals(strokes, page.strokes.toList())
        assertUndoable(document, RemoveElements.of(document.pages[1], document.pages[1].markups.toList()))
    }

    @Test
    fun elementsArePlacedOnTheirPageOrOnAnother() {
        val document = documentWithContent()
        val (first, second) = document.pages
        val stroke = first.strokes[0]
        val text = first.texts[0]
        val image = first.images[0]
        val moved = PlaceElements(
            first.id,
            listOf(
                StrokePlacement(stroke.id, AffineTransform(a = 2f, d = 2f, tx = 5f)),
                TextPlacement(text.id, 1f, 2f, 80f, 90f, 32f, autoWidth = false),
                BoxPlacement(image.id, 3f, 4f, 10f, 10f, 0f)
            )
        )
        assertUndoable(document, moved) {
            assertEquals(AffineTransform(a = 2f, d = 2f, tx = 5f), stroke.transform)
            assertEquals(Rect(25f, 20f, 35f, 30f), stroke.bounds)
            assertEquals(32f, text.fontSize)
        }
        // Undone, the stroke is back to the geometry it was drawn with
        assertTrue(stroke.transform.isIdentity)
        assertEquals(stroke.baseMesh.positions.toList(), stroke.mesh.positions.toList())

        val toSecond = PlaceElements(first.id, listOf(StrokePlacement(stroke.id, AffineTransform(ty = 5f))), second.id)
        assertUndoable(document, toSecond) {
            assertTrue(stroke in second.strokes && stroke !in first.strokes)
        }
        assertTrue(stroke in first.strokes)
    }

    @Test
    fun textsAndMarksChange() {
        val document = documentWithContent()
        val page = document.pages[0]
        val text = page.texts[0]
        assertUndoable(document, UpdateTexts(page.id, listOf(text.toData().copy(text = "changed", color = 0xFFFF0000.toInt())))) {
            assertEquals("changed", text.text)
        }
        val second = document.pages[1]
        val markup = second.markups[0]
        assertUndoable(document, RestyleMarkups(second.id, listOf(MarkupStyle(markup.id, TextMarkupKind.STRIKEOUT, 0xFF000000.toInt())))) {
            assertEquals(TextMarkupKind.STRIKEOUT, markup.kind)
        }
    }

    @Test
    fun pagesAreAddedRemovedReorderedAndFormatted() {
        val document = documentWithContent()
        val added = Page().apply { add(stroke(1f, 1f)) }
        added.orderKey = document.orderKeyAt(1)
        assertUndoable(document, AddPage.of(added)) {
            assertEquals(added, document.pages[1])
            assertEquals(listOf(0, 1, 2, 3), document.pages.map { it.index })
        }
        // A removed page comes back with its content (the same object: its render caches too)
        val removed = document.pages[1]
        assertUndoable(document, RemovePage(removed.id)) { assertTrue(removed !in document.pages) }
        assertSame(removed, document.pages[1])

        assertUndoable(document, assertNotNull(MovePages.toOrder(document, document.pages.map { it.id }.reversed()))) {
            assertEquals(3, document.pages[0].index + document.pages.size)
        }
        assertUndoable(document, SetPageFormat(document.pages[0].id, 148f, 210f, PageBackground.Grid()))
        assertUndoable(document, SetDocumentDefaults(297f, 210f, PageBackground.Dotted())) {
            assertEquals(PageBackground.Dotted(), document.pages[1].background)
        }
    }

    @Test
    fun theLastPageCanBeRemovedByAnotherDevice() {
        // Two devices removing the two pages at once agree: the document is empty
        val document = Document()
        assertNotNull(DocumentSession(document).submit(RemovePage(document.pages[0].id)))
        assertTrue(document.pages.isEmpty())
    }

    @Test
    fun resourcesAreKeptByUndo() {
        val document = Document()
        val session = DocumentSession(document)
        val image = image()
        val change = CompositeChange(
            listOf(AddResource.of("img", DocumentResource.ResourceType.IMAGE, "/tmp/img.png"), AddElements.of(document.pages[0], listOf(image)))
        )
        val undo = assertNotNull(session.submit(change))
        assertEquals("/tmp/img.png", document.findResource("img")?.path)
        session.submit(undo, ChangeOrigin.UNDO)
        assertTrue(document.pages[0].images.isEmpty())
        assertNotNull(document.findResource("img"))
    }

    @Test
    fun aChangeOfElementsThatAreGoneChangesNothing() {
        val document = documentWithContent()
        val session = DocumentSession(document)
        assertNull(session.submit(RemoveElements(document.pages[0].id, listOf("unknown"))))
        assertNull(session.submit(PlaceElements("unknown-page", emptyList())))
        // Elements already there are not added twice (present since the last addition)
        val page = document.pages[0]
        val strokes = page.strokes.toList()
        session.submit(AddElements.of(page, listOf(page.strokes[0])))
        assertEquals(strokes, page.strokes.toList())
    }

    // --- A long random session is undone and redone exactly ---

    @Test
    fun randomChangesAreUndoneAndRedoneExactly() {
        repeat(20) { seed -> undoAndRedoRandomChanges(Random(seed)) }
    }

    private fun undoAndRedoRandomChanges(random: Random) {
        val document = documentWithContent()
        val session = DocumentSession(document)
        val history = HistoryManager(session, maxHistorySize = 1000)
        val initial = document.snapshot()

        var made = 0
        repeat(60) {
            val change = randomChange(document, random) ?: return@repeat
            if (session.submit(change) != null) made++
        }
        val final = document.snapshot()

        repeat(made) { history.undo() }
        assertSameContent(initial, document.snapshot(), "undo of $made changes")
        repeat(made) { history.redo() }
        assertSameContent(final, document.snapshot(), "redo of $made changes")
    }

    private fun randomChange(document: Document, random: Random): DocumentChange? {
        val page = document.pages[random.nextInt(document.pages.size)]
        val elements = page.elements()
        fun someElements(): List<PageElement> = elements.shuffled(random).take(random.nextInt(1, 4))
        return when (random.nextInt(9)) {
            0 -> AddElements.of(page, List(random.nextInt(1, 3)) { stroke(random.nextFloat() * 100f, random.nextFloat() * 100f) } + text("t${random.nextInt()}"))
            1 -> if (elements.isEmpty()) null else RemoveElements.of(page, someElements())
            2 -> {
                val placements = someElements().mapNotNull { element ->
                    when (element) {
                        is StrokeElement -> StrokePlacement(element.id, AffineTransform(tx = random.nextFloat() * 10f, ty = random.nextFloat() * 10f))
                        is TextElement -> TextPlacement(element.id, random.nextFloat() * 50f, random.nextFloat() * 50f, 30f, random.nextFloat() * 90f, 16f, autoWidth = false)
                        is ImageElement -> BoxPlacement(element.id, random.nextFloat() * 50f, random.nextFloat() * 50f, 30f, 10f, random.nextFloat() * 90f)
                        else -> null
                    }
                }
                val target = document.pages[random.nextInt(document.pages.size)]
                if (placements.isEmpty()) null else PlaceElements(page.id, placements, target.id)
            }
            3 -> page.texts.firstOrNull()?.let { UpdateTexts(page.id, listOf(it.toData().copy(text = "u${random.nextInt()}"))) }
            4 -> AddPage.of(Page(orderKey = document.orderKeyAt(random.nextInt(document.pages.size + 1))).apply { add(stroke(5f, 5f)) })
            5 -> if (document.pages.size > 1) RemovePage(page.id) else null
            6 -> MovePages.toOrder(document, document.pages.map { it.id }.shuffled(random))
            7 -> SetPageFormat(page.id, 100f + random.nextInt(100), 100f + random.nextInt(100), if (random.nextBoolean()) PageBackground.Ruled() else null)
            else -> page.markups.firstOrNull()?.let {
                RestyleMarkups(page.id, listOf(MarkupStyle(it.id, TextMarkupKind.entries.random(random), random.nextInt())))
            }
        }
    }

    // --- Serialization ---

    @Test
    fun changesAreSerializedAsValues() {
        val document = documentWithContent()
        val (first, second) = document.pages
        val changes = listOf(
            AddElements.of(first, listOf(stroke(1f, 2f), text("serialized"), image(), markup())),
            RemoveElements.of(first, first.strokes.take(2)),
            PlaceElements(first.id, listOf(StrokePlacement(first.strokes[0].id, AffineTransform(tx = 3f))), second.id),
            UpdateTexts(first.id, listOf(first.texts[0].toData().copy(text = "x"))),
            RestyleMarkups(second.id, listOf(MarkupStyle(second.markups[0].id, TextMarkupKind.UNDERLINE, 1))),
            AddPage.of(Page(orderKey = "a").apply { add(stroke(5f, 5f)) }),
            RemovePage(second.id),
            MovePages(listOf(PageMove(first.id, "z"))),
            SetPageFormat(first.id, 148f, 210f, PageBackground.Grid()),
            SetDocumentDefaults(210f, 297f, PageBackground.Ruled()),
            CompositeChange(listOf(AddResource.of("r", DocumentResource.ResourceType.PDF, "/tmp/r.pdf"), RemovePage(first.id)))
        )
        val serializer = ListSerializer(DocumentChange.serializer())
        val json = Json.encodeToString(serializer, changes)
        assertEquals(changes, Json.decodeFromString(serializer, json))
    }

    @Test
    fun aDeserializedOperationIsAppliedToAnotherCopyOfTheDocument() {
        val document = documentWithContent()
        val copy = Document(id = document.id, addInitialPage = false)
        for (page in document.pages) copy.appendPage(page.toData().toPage(copy))
        copy.reindexPages()

        val session = DocumentSession(document, "device")
        val operations = mutableListOf<Operation>()
        session.addObserver { operations.add(it.operation) }
        val text = text("from another device")
        session.submit(AddElements.of(document.pages[0], listOf(text)))
        val sent = Json.encodeToString(Operation.serializer(), operations.single())
        assertTrue(DocumentSession(copy, "copy").receive(Json.decodeFromString(Operation.serializer(), sent)))

        assertSameContent(document.snapshot(), copy.snapshot(), "the copy")
        assertTrue(copy.pages[0].texts.none { it === text }, "the other copy has its own objects")
    }

    // --- Persistence ---

    @Test
    fun theEffectsAreWrittenInOrder() = runBlocking {
        val document = Document()
        val session = DocumentSession(document)
        val calls = mutableListOf<String>()
        val persistence = object : DocumentPersistence {
            override suspend fun savePage(document: Document, page: Page) {
                calls.add(if (page.removed != null) "page ${page.id} removed=true" else "page ${page.id} ${page.orderKey}")
            }

            override suspend fun saveStrokes(page: Page, strokes: List<StrokeElement>) {
                calls.add("strokes ${strokes.map { "${it.id} removed=${it.removed != null}" }} on ${page.id}")
            }

            override suspend fun touchDocument(document: Document, timestamp: Long) {
                calls.add("touch")
            }
        }
        val operations = mutableListOf<suspend DocumentPersistence.() -> Unit>()
        session.addObserver(ChangePersister { operations.add(it) })

        val stroke = stroke(1f, 1f)
        val page = Page(id = newElementId()).apply { add(stroke) }
        page.orderKey = document.orderKeyAt(1)
        val undo = assertNotNull(session.submit(AddPage.of(page)))
        suspend fun write() {
            operations.forEach { persistence.it() }
            operations.clear()
        }
        write()
        assertEquals(listOf("page ${page.id} ${page.orderKey}", "strokes [${stroke.id} removed=false] on ${page.id}", "touch"), calls)

        // Nothing is deleted: the removed stroke and the removed page are stored as such
        calls.clear()
        session.submit(RemoveElements.of(page, listOf(stroke)))
        write()
        assertEquals(listOf("strokes [${stroke.id} removed=true] on ${page.id}", "touch"), calls)

        calls.clear()
        session.submit(undo, ChangeOrigin.UNDO)
        write()
        assertEquals(listOf("page ${page.id} removed=true", "touch"), calls)
    }

    @Test
    fun theEffectsOfAnOperationAreWrittenInOneTransaction() = runBlocking {
        val document = Document()
        val session = DocumentSession(document)
        val calls = mutableListOf<String>()
        val persistence = object : DocumentPersistence {
            override suspend fun <T> transaction(block: suspend DocumentPersistence.() -> T): T {
                calls.add("begin")
                return block().also { calls.add("end") }
            }

            override suspend fun savePage(document: Document, page: Page) {
                calls.add("page")
            }

            override suspend fun saveStrokes(page: Page, strokes: List<StrokeElement>) {
                calls.add("strokes")
            }

            override suspend fun appendOperation(document: Document, operation: Operation) {
                calls.add("operation")
            }

            override suspend fun touchDocument(document: Document, timestamp: Long) {
                calls.add("touch")
            }
        }
        val operations = mutableListOf<suspend DocumentPersistence.() -> Unit>()
        session.addObserver(ChangePersister { operations.add(it) })

        val page = Page(id = newElementId()).apply { add(stroke(1f, 1f)) }
        page.orderKey = document.orderKeyAt(1)
        session.submit(AddPage.of(page))
        session.submit(AddElements.of(page, listOf(stroke(2f, 2f))))
        operations.forEach { persistence.it() }
        assertEquals(
            listOf("begin", "page", "strokes", "operation", "touch", "end") + listOf("begin", "strokes", "page", "operation", "touch", "end"),
            calls
        )
    }
}
