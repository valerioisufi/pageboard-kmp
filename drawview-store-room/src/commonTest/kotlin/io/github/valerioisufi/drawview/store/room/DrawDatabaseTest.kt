package io.github.valerioisufi.drawview.store.room

import io.github.valerioisufi.drawview.ink.InkStrokeEngine
import io.github.valerioisufi.drawview.ink.InkStrokeModeller
import io.github.valerioisufi.drawview.model.BrushConfig
import io.github.valerioisufi.drawview.model.DrawCustomElement
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawImage
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.DrawResource
import io.github.valerioisufi.drawview.model.DrawStroke
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.DrawTextMarkup
import io.github.valerioisufi.drawview.model.ElementOrder
import io.github.valerioisufi.drawview.model.FractionalIndex
import io.github.valerioisufi.drawview.model.InputToolType
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.Stamp
import io.github.valerioisufi.drawview.model.StrokePoint
import io.github.valerioisufi.drawview.model.StrokeTool
import io.github.valerioisufi.drawview.model.TextAlignment
import io.github.valerioisufi.drawview.model.TextMarkupKind
import io.github.valerioisufi.drawview.model.Versions
import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.model.change.AddPage
import io.github.valerioisufi.drawview.model.change.DocumentSession
import io.github.valerioisufi.drawview.model.change.Operation
import io.github.valerioisufi.drawview.model.change.OperationId
import io.github.valerioisufi.drawview.model.change.RemoveElements
import io.github.valerioisufi.drawview.model.change.RemovePage
import io.github.valerioisufi.drawview.model.change.StrokeData
import io.github.valerioisufi.drawview.model.change.UpdateTexts
import io.github.valerioisufi.drawview.model.change.snapshot
import io.github.valerioisufi.drawview.model.change.toData
import io.github.valerioisufi.drawview.model.geometry.AffineTransform
import io.github.valerioisufi.drawview.model.geometry.Rect
import io.github.valerioisufi.drawview.model.newElementId
import io.github.valerioisufi.drawview.store.room.db.*
import io.github.valerioisufi.drawview.store.room.repository.DrawDocumentRepository
import io.github.valerioisufi.drawview.store.room.repository.FileRepository
import io.github.valerioisufi.drawview.store.testing.checkFolderOperations
import io.github.valerioisufi.drawview.store.testing.checkOperationProofs
import io.github.valerioisufi.drawview.store.testing.checkOutline
import io.github.valerioisufi.drawview.store.testing.checkPageFingerprint
import io.github.valerioisufi.drawview.store.testing.checkTrash
import kotlin.test.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class DrawDatabaseTest {

    private lateinit var database: DrawDatabase
    private lateinit var repository: DrawDocumentRepository

    @BeforeTest
    fun setup() {
        database = createDrawDatabase(getInMemoryDatabaseBuilder())
        repository = DrawDocumentRepository(database, InkStrokeModeller)
    }

    @AfterTest
    fun tearDown() {
        database.close()
    }

    @Test
    fun testStrokeInputCodecRoundTrip() {
        val originalPoints = listOf(
            StrokePoint(10.5f, 20.25f, 0.6f, 1000L),
            StrokePoint(15.0f, 30.0f, 0.8f, 1016L),
            StrokePoint(25.75f, 45.5f, 0.95f, 1032L)
        )

        val encoded = StrokeInputCodec.encode(originalPoints)
        assertTrue(encoded.isNotEmpty())
        assertEquals(6 + 3 * 20, encoded.size)

        val decoded = StrokeInputCodec.decode(encoded)
        assertEquals(originalPoints.size, decoded.size)

        for (i in originalPoints.indices) {
            assertEquals(originalPoints[i].x, decoded[i].x)
            assertEquals(originalPoints[i].y, decoded[i].y)
            assertEquals(originalPoints[i].pressure, decoded[i].pressure)
            assertEquals(originalPoints[i].timestampMs, decoded[i].timestampMs)
        }
    }

    @Test
    fun testFolderAndDocumentCreation() = runTest {
        val folderDao = database.folderDao()
        val documentDao = database.documentDao()

        val folderId = newElementId()
        folderDao.insert(FolderEntity(id = folderId, name = "Math Notes"))

        val docId = newElementId()
        documentDao.insert(
            DocumentEntity(
                id = docId,
                folderId = folderId,
                name = "Calculus Lecture 1",
                defaultWidth = 210f,
                defaultHeight = 297f,
                defaultBackground = PageBackground.Grid(spacingMm = 5f)
            )
        )

        val doc = documentDao.getDocumentById(docId)
        assertNotNull(doc)
        assertEquals("Calculus Lecture 1", doc.name)
        assertEquals(folderId, doc.folderId)
        assertTrue(doc.defaultBackground is PageBackground.Grid)
        // Timestamps are wall-clock epoch milliseconds (they used to be always ~0)
        assertTrue(doc.createdAt > 1_600_000_000_000L)
    }

    @Test
    fun testDocumentWithPagesAndStrokesPersistence() = runTest {
        val createdDoc = repository.createDocument("Physics Notes", 210f, 297f, PageBackground.Solid())
        val docId = createdDoc.id

        val loadedInitial = repository.loadDocument(docId)
        assertNotNull(loadedInitial)
        assertEquals("Physics Notes", loadedInitial.title)
        assertEquals(1, loadedInitial.pages.size)

        val points = listOf(
            StrokePoint(50f, 50f, 0.5f, 100L),
            StrokePoint(55f, 60f, 0.7f, 116L),
            StrokePoint(70f, 80f, 0.9f, 132L),
            StrokePoint(90f, 100f, 0.4f, 148L)
        )

        // The page row has the identifier of the page of the model
        val pagesFromDb = database.pageDao().getPagesForDocument(docId)
        assertEquals(listOf(createdDoc.pages.single().id), pagesFromDb.map { it.id })

        val strokeEntity = StrokeEntity(
            id = newElementId(),
            pageId = pagesFromDb.single().id,
            orderKey = ElementOrder.UNASSIGNED,
            color = 0xFFFF0000.toInt(),
            size = 3.5f,
            toolType = StrokeTool.PRESSURE_PEN.name,
            brushFamily = StrokeTool.PRESSURE_PEN.name,
            inputs = StrokeInputCodec.encode(points),
            transform = AffineTransform.IDENTITY,
            added = Stamp.ZERO,
            removed = null,
            placed = Stamp.ZERO,
            ordered = Stamp.ZERO,
            updated = Stamp.ZERO
        )

        database.strokeDao().upsertAll(listOf(strokeEntity))

        // Load document through repository and verify stroke reconstruction
        val loadedDoc = repository.loadDocument(docId)
        assertNotNull(loadedDoc)
        assertEquals(1, loadedDoc.pages.size)
        val loadedPage = loadedDoc.pages[0]
        assertEquals(1, loadedPage.strokes.size)

        val loadedStroke = loadedPage.strokes[0]
        assertEquals(strokeEntity.id, loadedStroke.id)
        assertEquals(StrokeTool.PRESSURE_PEN, loadedStroke.brushConfig.tool)
        assertEquals(3.5f, loadedStroke.brushConfig.size)
        assertEquals(points.size, loadedStroke.mesh.points.size)
        assertTrue(loadedStroke.mesh.vertexCount > 0, "the mesh is rebuilt from the stored inputs")

        for (i in points.indices) {
            assertEquals(points[i].x, loadedStroke.mesh.points[i].x)
            assertEquals(points[i].y, loadedStroke.mesh.points[i].y)
            assertEquals(points[i].pressure, loadedStroke.mesh.points[i].pressure)
            assertEquals(points[i].timestampMs, loadedStroke.mesh.points[i].timestampMs)
        }
    }

    @Test
    fun testEditorPersistenceRoundTrip() = runTest {
        val doc = repository.createDocument("Editor", 148f, 210f, PageBackground.Ruled())
        val page = doc.pages[0]

        // Stroke saved through the editor port (page millimeters)
        val points = (0..10).map { StrokePoint(10f + it * 3f, 20f + it, 0.5f, 1000L + it * 16L) }
        val brush = BrushConfig(tool = StrokeTool.HIGHLIGHTER, size = 4f, color = 0x40FFFF00, epsilon = 0.02f)
        val mesh = assertNotNull(InkStrokeEngine.buildMesh(points, brush, InputToolType.STYLUS))
        // Placed by a transformation (moved and scaled with the lasso)
        val transform = AffineTransform(a = 2f, d = 2f, tx = 5f, ty = -3f)
        val stroke = DrawStroke(mesh = mesh, brushConfig = brush, transform = transform)
        repository.saveStrokes(page, listOf(stroke))

        // Text
        val text = DrawText().apply {
            this.text = "**Hello** $\\frac{a}{b}$"
            x = 12f; y = 30f; width = 80f; height = 14f
            alignment = TextAlignment.CENTER
            autoWidth = true
            color = 0xFF112233.toInt()
            fontSize = 20f
            background = 0xFFD7E8FA.toInt()
        }
        repository.saveText(page, text)

        // Image referencing a document resource
        val resourceId = newElementId()
        repository.saveResource(doc, DrawResource(resourceId, DrawResource.ResourceType.IMAGE, "/tmp/image.png"))
        val image = DrawImage().apply {
            this.resourceId = resourceId
            x = 5f; y = 6f; width = 50f; height = 40f; rotation = 15f
        }
        repository.saveImage(page, image)

        // A second page with a custom background, then soft-deleted
        val second = DrawPage(index = 1, widthMm = 148f, heightMm = 210f, background = PageBackground.Grid()).apply {
            hasCustomBackground = true
        }
        second.orderKey = doc.orderKeyAt(1)
        repository.savePage(doc, second)

        val loaded = assertNotNull(repository.loadDocument(doc.id))
        assertEquals(2, loaded.pages.size)
        // What the library tells of the content: the pages, no PDF
        val summary = FileRepository(database).getAllDocumentsWithContentFlow().first().single { it.document.id == doc.id }
        assertEquals(2, summary.pageCount)
        assertFalse(summary.hasPdf)
        assertFalse(loaded.pages[0].hasCustomBackground)
        assertTrue(loaded.pages[0].background is PageBackground.Ruled)
        assertTrue(loaded.pages[1].hasCustomBackground)
        assertTrue(loaded.pages[1].background is PageBackground.Grid)

        val loadedStroke = loaded.pages[0].strokes.single()
        assertEquals(StrokeTool.HIGHLIGHTER, loadedStroke.brushConfig.tool)
        // The stroke as it was drawn and its placement
        assertEquals(transform, loadedStroke.transform)
        assertEquals(stroke.mesh.points, loadedStroke.mesh.points)
        assertEquals(8f, loadedStroke.brushConfig.size)
        assertEquals(4f, loadedStroke.baseBrush.size)
        assertEquals(InputToolType.STYLUS, loadedStroke.mesh.inputToolType)
        assertEquals(0x40FFFF00, loadedStroke.brushConfig.color)

        val loadedText = loaded.pages[0].texts.single()
        assertEquals(text.text, loadedText.text)
        assertEquals(TextAlignment.CENTER, loadedText.alignment)
        assertTrue(loadedText.autoWidth)
        assertEquals(20f, loadedText.fontSize)
        assertEquals(0xFFD7E8FA.toInt(), loadedText.background)

        val loadedImage = loaded.pages[0].images.single()
        assertEquals(15f, loadedImage.rotation)
        assertEquals("/tmp/image.png", loaded.findResource(loadedImage.resourceId)?.path)

        // The identifiers on every device are stored
        assertEquals(doc.id, loaded.id)
        assertEquals(listOf(page.id, second.id), loaded.pages.map { it.id })
        assertEquals(stroke.id, loadedStroke.id)
        assertEquals(text.id, loadedText.id)
        assertEquals(image.id, loadedImage.id)
        assertEquals(resourceId, loadedImage.resourceId)

        // A removed page is kept, hidden, with its content; then put back (undo)
        val removedPage = loaded.pages[1]
        removedPage.removed = Stamp(1L, 0, "device")
        repository.savePage(loaded, removedPage)
        val withoutPage = assertNotNull(repository.loadDocument(doc.id))
        assertEquals(1, withoutPage.pages.size)
        assertEquals(removedPage.removed, withoutPage.removedPages[removedPage.id]?.removed)
        removedPage.removed = null
        removedPage.orderKey = FractionalIndex.between(null, loaded.pages[0].orderKey)
        repository.savePage(loaded, removedPage)
        assertEquals(listOf(second.id, page.id), assertNotNull(repository.loadDocument(doc.id)).pages.map { it.id })

        // A removed stroke is kept, hidden, and shown again (redo) with the same identifier
        loadedStroke.removed = Stamp(2L, 0, "device")
        repository.saveStrokes(page, listOf(loadedStroke))
        val withoutStroke = assertNotNull(repository.loadDocument(doc.id)).pages.single { it.id == page.id }
        assertTrue(withoutStroke.strokes.isEmpty())
        assertEquals(listOf(stroke.id), withoutStroke.removedElements.map { it.id })
        loadedStroke.removed = null
        repository.saveStrokes(page, listOf(loadedStroke))
        assertEquals(stroke.id, assertNotNull(repository.loadDocument(doc.id)).pages.single { it.id == page.id }.strokes.single().id)
    }

    @Test
    fun aDocumentReceivedIsStoredWhole() = runTest {
        // Changed by a session: stamps, removed elements and a removed page
        val document = DrawDocument(title = "Received")
        val session = DocumentSession(document, "other-device")
        val strokes = List(3) { i ->
            val points = (0 until 6).map { StrokePoint(10f + i * 20f + it, 20f + it, 0.5f, it * 8L) }
            val brush = BrushConfig(tool = StrokeTool.PRESSURE_PEN, size = 0.5f, color = 0xFF000000.toInt())
            DrawStroke(mesh = assertNotNull(InkStrokeEngine.buildMesh(points, brush, InputToolType.STYLUS)), brushConfig = brush)
        }
        val page = document.pages[0]
        session.submit(AddElements.of(page, strokes))
        session.submit(RemoveElements.of(page, listOf(strokes[1])))
        val second = DrawPage(orderKey = document.orderKeyAt(1))
        session.submit(AddPage.of(second))
        session.submit(RemovePage(second.id))
        // A text changed, on a page removed by a device that had not seen it: the page stays
        val third = DrawPage(orderKey = document.orderKeyAt(1))
        session.submit(AddPage.of(third))
        val text = DrawText().apply { this.text = "written"; x = 10f; y = 10f; width = 40f; height = 8f }
        session.submit(AddElements.of(third, listOf(text)))
        session.submit(UpdateTexts(third.id, listOf(text.toData().copy(text = "changed"))))
        val removal = Stamp(session.clock.next().time + 1, 0, "another-device")
        session.receive(Operation(OperationId("another-device", 1), removal, RemovePage(third.id, Versions.NONE)))

        repository.insertDocument(document, folderId = null)
        val loaded = assertNotNull(repository.loadDocument(document.id))
        assertEquals(document.snapshot(), loaded.snapshot())
        assertEquals(listOf(strokes[1].id), loaded.pages[0].removedElements.map { it.id })
        assertEquals(setOf(second.id), loaded.removedPages.keys)
        assertEquals(listOf(document.pages[0].id, third.id), loaded.pages.map { it.id })
        assertEquals(removal, loaded.pages[1].removed)
    }

    @Test
    fun testTextMarkupPersistence() = runTest {
        val doc = repository.createDocument("Slides", 210f, 297f, PageBackground.Solid())
        val page = doc.pages[0]
        val markup = DrawTextMarkup(
            resourceId = "7",
            pdfPageIndex = 2,
            pdfWidthPt = 595f,
            pdfHeightPt = 842f,
            startIndex = 10,
            endIndex = 25,
            text = "hash\nfunctions",
            rects = listOf(Rect(72f, 100f, 198.5f, 124f), Rect(72f, 130f, 144f, 154f)),
            kind = TextMarkupKind.HIGHLIGHT,
            color = 0xFFFFF176.toInt()
        )
        repository.saveTextMarkup(page, markup)

        // A change of style updates the row
        markup.kind = TextMarkupKind.UNDERLINE
        markup.color = 0xFFE53935.toInt()
        repository.saveTextMarkup(page, markup)

        val loaded = assertNotNull(repository.loadDocument(doc.id)).pages[0].markups.single()
        assertEquals(markup.id, loaded.id)
        assertEquals("7", loaded.resourceId)
        assertEquals(2, loaded.pdfPageIndex)
        assertEquals(595f, loaded.pdfWidthPt)
        assertEquals(842f, loaded.pdfHeightPt)
        assertEquals(10, loaded.startIndex)
        assertEquals(25, loaded.endIndex)
        assertEquals(markup.text, loaded.text)
        assertEquals(markup.rects, loaded.rects)
        assertEquals(TextMarkupKind.UNDERLINE, loaded.kind)
        assertEquals(0xFFE53935.toInt(), loaded.color)

        // A removed mark is kept, hidden, and shown again (undo) with the same identifier
        loaded.removed = Stamp(1L, 0, "device")
        repository.saveTextMarkup(page, loaded)
        assertTrue(assertNotNull(repository.loadDocument(doc.id)).pages[0].markups.isEmpty())
        loaded.removed = null
        repository.saveTextMarkup(page, loaded)
        assertEquals(markup.id, assertNotNull(repository.loadDocument(doc.id)).pages[0].markups.single().id)
    }

    @Test
    fun thePreferencesAreKeptAsTextByKey() = runTest {
        val store = RoomDocumentStore(database, InkStrokeModeller)
        assertNull(store.preference("drawview.editor"))
        store.setPreference("drawview.editor", "{\"isStylusOnlyMode\":true}")
        store.setPreference("other", "1")
        assertEquals("{\"isStylusOnlyMode\":true}", store.preference("drawview.editor"))
        store.setPreference("drawview.editor", "{}")
        assertEquals("{}", store.preference("drawview.editor"))
        store.setPreference("drawview.editor", null)
        assertNull(store.preference("drawview.editor"))
        assertEquals("1", store.preference("other"))
    }

    @Test
    fun testDocumentsFlowAndDeletion() = runTest {
        val files = FileRepository(database)
        val a = repository.createDocument("A", 210f, 297f, PageBackground.Solid())
        repository.createDocument("B", 210f, 297f, PageBackground.Solid())
        assertEquals(2, files.getAllDocumentsFlow().first().size)
        assertTrue(files.isDocumentNameAvailable(a.id, "A renamed"))
        assertFalse(files.isDocumentNameAvailable(a.id, "B"))
        assertTrue(files.deleteDocument(a.id))
        val remaining = files.getAllDocumentsFlow().first()
        assertEquals(listOf("B"), remaining.map { it.name })
        assertNull(repository.loadDocument(a.id))
    }

    @Test
    fun testFolderOperations() = runTest {
        checkFolderOperations(RoomDocumentStore(database, InkStrokeModeller))
    }

    @Test
    fun theTrashIsKept() = runTest {
        checkTrash(RoomDocumentStore(database, InkStrokeModeller))
    }

    @Test
    fun theOutlineIsTheDocumentWithoutItsStrokes() = runTest {
        checkOutline(RoomDocumentStore(database, InkStrokeModeller))
    }

    @Test
    fun theFingerprintOfAPageChangesWithIt() = runTest {
        checkPageFingerprint(RoomDocumentStore(database, InkStrokeModeller))
    }

    @Test
    fun anOperationFailingLeavesNoneOfItsRows() = runTest {
        val store = RoomDocumentStore(database, InkStrokeModeller)
        val document = store.createDocument("All or none")
        val page = document.pages.single()
        val modifiedAt = store.documents.first().single().modifiedAt
        val stroke = StrokeData(
            id = newElementId(),
            points = listOf(StrokePoint(10f, 10f, 0.5f, 1L), StrokePoint(20f, 12f, 0.5f, 9L)),
            tool = StrokeTool.PRESSURE_PEN,
            size = 0.5f,
            color = 0xFF000000.toInt()
        ).toElement()

        assertFailsWith<IllegalStateException> {
            store.persistence.transaction {
                saveStrokes(page, listOf(stroke))
                touchDocument(document, modifiedAt + 1_000L)
                error("the operation fails before its end")
            }
        }
        assertTrue(assertNotNull(store.loadDocument(document.id)).pages.single().strokes.isEmpty(), "the stroke is not stored")
        assertEquals(modifiedAt, store.documents.first().single().modifiedAt, "nor the time of the change")

        store.persistence.transaction {
            saveStrokes(page, listOf(stroke))
            touchDocument(document, modifiedAt + 1_000L)
        }
        assertEquals(listOf(stroke.id), assertNotNull(store.loadDocument(document.id)).pages.single().strokes.map { it.id })
        assertEquals(modifiedAt + 1_000L, store.documents.first().single().modifiedAt)
    }

    @Test
    fun theProofsOfTheOperationsAreKept() = runTest {
        checkOperationProofs(RoomDocumentStore(database, InkStrokeModeller)) { RoomDocumentStore(database, InkStrokeModeller) }
    }

    @Test
    fun anElementOfAnApplicationIsStoredWithItsBytes() = runTest {
        val doc = repository.createDocument("Shapes", 210f, 297f, PageBackground.Solid())
        val page = doc.pages[0]
        val element = DrawCustomElement("com.example.shape").apply {
            version = 2
            payload = byteArrayOf(9, 8, 7)
            x = 10f
            y = 20f
            width = 30f
            height = 40f
            rotation = 15f
        }
        page.add(element)
        repository.saveCustomElement(page, element)

        // Stored as it is: the store never reads the value
        val loaded = assertNotNull(repository.loadDocument(doc.id)).pages[0].customElements.single()
        assertEquals("com.example.shape", loaded.type)
        assertEquals(2, loaded.version)
        assertContentEquals(byteArrayOf(9, 8, 7), loaded.payload)
        assertEquals(Rect(10f, 20f, 40f, 60f), Rect(loaded.x, loaded.y, loaded.x + loaded.width, loaded.y + loaded.height))
        assertEquals(15f, loaded.rotation)
        assertEquals(element.order, loaded.order)
    }
}
