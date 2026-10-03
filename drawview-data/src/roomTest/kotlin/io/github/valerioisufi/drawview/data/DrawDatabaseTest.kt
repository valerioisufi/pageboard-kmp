package io.github.valerioisufi.drawview.data

import androidx.compose.ui.graphics.Color
import io.github.valerioisufi.drawview.core.controller.ToolPreset
import io.github.valerioisufi.drawview.core.engine.InkStrokeEngine
import io.github.valerioisufi.drawview.core.model.BrushConfig
import io.github.valerioisufi.drawview.core.model.DrawImage
import io.github.valerioisufi.drawview.core.model.DrawPage
import io.github.valerioisufi.drawview.core.model.DrawResource
import io.github.valerioisufi.drawview.core.model.DrawStroke
import io.github.valerioisufi.drawview.core.model.DrawText
import io.github.valerioisufi.drawview.core.model.InputToolType
import io.github.valerioisufi.drawview.core.model.PageBackground
import io.github.valerioisufi.drawview.core.model.StrokePoint
import io.github.valerioisufi.drawview.core.model.StrokeTool
import io.github.valerioisufi.drawview.core.model.Tool
import io.github.valerioisufi.drawview.data.db.*
import io.github.valerioisufi.drawview.data.repository.DrawDocumentRepository
import io.github.valerioisufi.drawview.data.repository.FileRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DrawDatabaseTest {

    private lateinit var database: DrawDatabase
    private lateinit var repository: DrawDocumentRepository

    @BeforeTest
    fun setup() {
        database = createDrawDatabase(getInMemoryDatabaseBuilder())
        repository = DrawDocumentRepository(database)
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

        val folderId = folderDao.insert(
            FolderEntity(name = "Math Notes")
        ).toInt()
        assertTrue(folderId > 0)

        val docId = documentDao.insert(
            DocumentEntity(
                folderId = folderId,
                name = "Calculus Lecture 1",
                defaultWidth = 210f,
                defaultHeight = 297f,
                defaultBackground = PageBackground.Grid(spacingMm = 5f)
            )
        ).toInt()
        assertTrue(docId > 0)

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
        val createdDoc = repository.createNewDefaultDocument("Physics Notes")
        val docId = createdDoc.id.toInt()
        assertTrue(docId > 0)

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

        // Query the actual page from DB to get its generated id
        val pagesFromDb = database.pageDao().getPagesForDocument(docId)
        assertEquals(1, pagesFromDb.size)
        val realPageId = pagesFromDb[0].id

        val strokeEntity = StrokeEntity(
            pageId = realPageId,
            zIndex = 0,
            color = 0xFFFF0000.toInt(),
            size = 3.5f,
            toolType = StrokeTool.PRESSURE_PEN.name,
            brushFamily = StrokeTool.PRESSURE_PEN.name,
            inputs = StrokeInputCodec.encode(points)
        )

        val savedStrokeId = database.strokeDao().insert(strokeEntity).toInt()
        assertTrue(savedStrokeId > 0)

        // Load document through repository and verify stroke reconstruction
        val loadedDoc = repository.loadDocument(docId)
        assertNotNull(loadedDoc)
        assertEquals(1, loadedDoc.pages.size)
        val loadedPage = loadedDoc.pages[0]
        assertEquals(1, loadedPage.strokes.size)

        val loadedStroke = loadedPage.strokes[0]
        assertEquals(savedStrokeId.toString(), loadedStroke.id)
        assertEquals(savedStrokeId, loadedStroke.dbId)
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
        assertTrue(page.dbId > 0)

        // Stroke saved through the editor port (page millimeters)
        val points = (0..10).map { StrokePoint(10f + it * 3f, 20f + it, 0.5f, 1000L + it * 16L) }
        val brush = BrushConfig(tool = StrokeTool.HIGHLIGHTER, size = 4f, color = Color(0x40FFFF00), epsilon = 0.02f)
        val mesh = assertNotNull(InkStrokeEngine.buildMesh(points, brush, InputToolType.STYLUS))
        val stroke = DrawStroke(mesh = mesh, brushConfig = brush, bounds = mesh.computeBounds())
        repository.saveNewStrokes(page, listOf(stroke))
        assertTrue(stroke.dbId > 0)

        // Text with the columns added in schema v2
        val text = DrawText().apply {
            this.text = "**Hello** $\\frac{a}{b}$"
            x = 12f; y = 30f; width = 80f; height = 14f
            alignment = 1
            isMarkdownEnabled = false
            color = 0xFF112233.toInt()
            fontSize = 20f
        }
        repository.saveText(page, text)
        assertTrue(text.dbId > 0)

        // Image referencing a document resource
        val resourceId = repository.addResource(doc, DrawResource.ResourceType.IMAGE, "/tmp/image.png")
        val image = DrawImage().apply {
            this.resourceId = resourceId
            x = 5f; y = 6f; width = 50f; height = 40f; rotation = 15f
        }
        repository.saveImage(page, image)

        // A second page with a custom background, then soft-deleted
        val second = DrawPage(index = 1, widthMm = 148f, heightMm = 210f, background = PageBackground.Grid()).apply {
            hasCustomBackground = true
        }
        repository.insertPage(doc, second)
        assertTrue(second.dbId > 0)

        val loaded = assertNotNull(repository.loadDocument(doc.dbId))
        assertEquals(2, loaded.pages.size)
        assertFalse(loaded.pages[0].hasCustomBackground)
        assertTrue(loaded.pages[0].background is PageBackground.Ruled)
        assertTrue(loaded.pages[1].hasCustomBackground)
        assertTrue(loaded.pages[1].background is PageBackground.Grid)

        val loadedStroke = loaded.pages[0].strokes.single()
        assertEquals(StrokeTool.HIGHLIGHTER, loadedStroke.brushConfig.tool)
        assertEquals(InputToolType.STYLUS, loadedStroke.mesh.inputToolType)
        assertEquals(0x40FFFF00, loadedStroke.brushConfig.color.value.toLong().let { (it ushr 32).toInt() })

        val loadedText = loaded.pages[0].texts.single()
        assertEquals(text.text, loadedText.text)
        assertEquals(1, loadedText.alignment)
        assertFalse(loadedText.isMarkdownEnabled)
        assertEquals(20f, loadedText.fontSize)

        val loadedImage = loaded.pages[0].images.single()
        assertEquals(15f, loadedImage.rotation)
        assertEquals("/tmp/image.png", loaded.findResource(loadedImage.resourceId)?.path)

        // Soft delete and restore
        repository.softDeletePage(loaded, loaded.pages[1], 1)
        assertEquals(1, assertNotNull(repository.loadDocument(doc.dbId)).pages.size)

        // Deleting the stroke resets its dbId so that a redo re-inserts it
        repository.deleteStrokes(listOf(loadedStroke))
        assertEquals(0, loadedStroke.dbId)
        assertTrue(assertNotNull(repository.loadDocument(doc.dbId)).pages[0].strokes.isEmpty())
    }

    @Test
    fun testUserPreferences() = runTest {
        repository.updateStylusMode(true)
        repository.updateLastSelectedTool("HIGHLIGHTER")
        repository.updateTextDefaults(fontSize = 18f, color = 0xFF00FF00.toInt(), isLatex = true)

        val prefs = database.preferencesDao().getPreferences()
        assertNotNull(prefs)
        assertTrue(prefs.isStylusOnlyMode)
        assertEquals("HIGHLIGHTER", prefs.lastSelectedTool)
        assertEquals(18f, prefs.defaultTextFontSize)
        assertEquals(0xFF00FF00.toInt(), prefs.defaultTextColor)
        assertTrue(prefs.defaultTextIsLatex)
    }

    @Test
    fun testEditorPreferencesMapping() = runTest {
        repository.savePresets(Tool.INK_PEN, listOf(ToolPreset(0.7f, 0xFF123456.toInt(), StrokeTool.PRESSURE_PEN)))
        repository.saveLastSelectedTool(Tool.LAZO)
        val prefs = repository.drawPreferencesFlow.first()
        assertEquals(Tool.LAZO, prefs.lastSelectedTool)
        assertEquals(listOf(ToolPreset(0.7f, 0xFF123456.toInt(), StrokeTool.PRESSURE_PEN)), prefs.presets[Tool.INK_PEN])
        // Legacy family names map to the eraser and lasso brushes
        assertEquals(StrokeTool.ERASER, prefs.presets.getValue(Tool.ERASER).first().family)
        assertEquals(StrokeTool.LASSO, prefs.presets.getValue(Tool.LAZO).first().family)
    }

    @Test
    fun testDocumentsFlowAndDeletion() = runTest {
        val files = FileRepository(database)
        val a = repository.createDocument("A", 210f, 297f, PageBackground.Solid())
        repository.createDocument("B", 210f, 297f, PageBackground.Solid())
        assertEquals(2, files.getAllDocumentsFlow().first().size)
        assertTrue(files.renameDocument(a.dbId, "A renamed"))
        assertTrue(files.deleteDocument(a.dbId))
        val remaining = files.getAllDocumentsFlow().first()
        assertEquals(listOf("B"), remaining.map { it.name })
        assertNull(repository.loadDocument(a.dbId))
    }

    @Test
    fun testFolderOperations() = runTest {
        checkFolderOperations(RoomDocumentStore(database))
    }
}
