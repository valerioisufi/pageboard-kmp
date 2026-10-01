package io.github.valerioisufi.drawview.core

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.valerioisufi.drawview.core.camera.CalcPage
import io.github.valerioisufi.drawview.core.camera.CameraPhysicsEngine
import io.github.valerioisufi.drawview.core.geometry.*
import io.github.valerioisufi.drawview.core.history.*
import io.github.valerioisufi.drawview.core.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.*

class GeometryAndPhysicsTest {

    // --- 1. PHYSICAL DIMENSIONS & MEASURE TESTS ---

    @Test
    fun testMeasureConversions() {
        val tenMm = 10f.mm
        assertEquals(1f, tenMm.cm, 0.001f)
        assertEquals(10f / 25.4f, tenMm.inch, 0.001f)
        assertEquals(10f * 2.83465f, tenMm.pt, 0.01f)

        val oneInch = 1.inch
        assertEquals(25.4f, oneInch.mm, 0.001f)
        assertEquals(2.54f, oneInch.cm, 0.001f)
    }

    @Test
    fun testDimensionA4Calculations() {
        val a4 = Dimension.A4()
        assertEquals(210f, a4.width.mm, 0.01f)
        assertEquals(297f, a4.height.mm, 0.01f)

        // Aspect ratio height calculation from width in px
        val targetWidthPx = 1000f.px
        val expectedHeightPx = (297f * 1000f) / 210f
        assertEquals(expectedHeightPx, a4.calcHeightFromWidthPx(targetWidthPx), 0.1f)

        // Screen mapping
        val pageRect = Rect(100f, 200f, 1100f, 1614.28f)
        val screenX = 600f // halfway
        val mappedMmX = a4.calcXMm(screenX, pageRect)
        assertEquals(105f, mappedMmX, 0.1f) // halfway of 210mm
    }

    // --- 2. 2D AFFINE MATRIX TESTS ---

    @Test
    fun testMatrix2DTransformations() {
        val m = Matrix2D()
        val p = m.mapPoint(10f, 20f)
        assertEquals(10f, p.x)
        assertEquals(20f, p.y)

        // Scale by 2 and translate by (50, 100)
        m.postScale(2f, 2f)
        m.postTranslate(50f, 100f)

        // (10, 20) * 2 + (50, 100) = (70, 140)
        val pTransformed = m.mapPoint(10f, 20f)
        assertEquals(70f, pTransformed.x, 0.001f)
        assertEquals(140f, pTransformed.y, 0.001f)

        // Inverse
        val inv = Matrix2D()
        assertTrue(m.invert(inv))
        val pRestored = inv.mapPoint(70f, 140f)
        assertEquals(10f, pRestored.x, 0.001f)
        assertEquals(20f, pRestored.y, 0.001f)

        // Rect mapping
        val rect = Rect(0f, 0f, 10f, 10f)
        val mappedRect = m.mapRect(rect)
        assertEquals(50f, mappedRect.left, 0.01f)
        assertEquals(100f, mappedRect.top, 0.01f)
        assertEquals(70f, mappedRect.right, 0.01f)
        assertEquals(120f, mappedRect.bottom, 0.01f)
    }

    // --- 3. CALC PAGE LAYOUT & BINARY SEARCH VISIBILITY ---

    @Test
    fun testCalcPageLayoutAndVisibility() {
        val calcPage = CalcPage(density = 1f)
        val doc = DrawDocument()
        doc.addPage() // page 1
        doc.addPage() // page 2

        assertEquals(3, doc.pages.size)

        val windowRect = Rect(0f, 0f, 800f, 1200f)
        calcPage.calcPagesRectOnWindow(doc.pages, windowRect)

        assertEquals(3, calcPage.pagesRectOnWindow.size)
        assertTrue(calcPage.contentRect.height > 0f)

        // Visible pages with identity matrix
        val matrix = Matrix2D()
        val visible = calcPage.getPagesRectOnWindowTransformation(windowRect, matrix)
        assertTrue(visible.isNotEmpty())
        assertEquals(0, visible.first().index)
    }

    // --- 4. CAMERA PHYSICS SPRING & INERTIA ENGINE ---

    @Test
    fun testCameraPhysicsEngine() {
        var content = Rect(0f, 0f, 800f, 2000f)
        val engine = CameraPhysicsEngine(density = 1f, getContentRect = { content })
        engine.setViewport(800f, 1000f)

        // Drag down by 200px (overscroll / bounce zone)
        engine.onDragStart()
        engine.onDrag(dx = 0f, dy = 200f, scaleFactor = 1f, focusX = 400f, focusY = 500f)
        engine.onRelease(0f, 0f)

        assertTrue(engine.isAnimating())

        // Run simulation steps
        for (i in 0 until 100) {
            engine.update(16L) // ~60 FPS steps
        }

        // After settling, it should spring back to boundary (top padding = 40px)
        assertFalse(engine.isAnimating())
        val finalPos = engine.getCurrentTranslation()
        assertEquals(40f, finalPos.y, 2f)
    }

    // --- 5. HISTORY MANAGER (UNDO/REDO) TESTS ---

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testHistoryManagerUndoRedo() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val doc = DrawDocument()
        val history = HistoryManager(coroutineScope = testScope, maxHistorySize = 10)

        val context = object : DrawingActionContext {
            override val document: DrawDocument = doc
        }

        val dummyMesh = StrokeMesh(
            positions = floatArrayOf(0f, 0f, 10f, 10f),
            indices = shortArrayOf(0, 1, 2),
            color = androidx.compose.ui.graphics.Color.Black,
            tool = StrokeTool.PRESSURE_PEN,
            size = 5f
        )
        val stroke = DrawStroke(
            mesh = dummyMesh,
            brushConfig = BrushConfig(),
            bounds = Rect(0f, 0f, 10f, 10f)
        )

        // Add stroke
        doc.pages[0].strokes.add(stroke)
        history.addHistoryAction(AddStrokesAction(0, stroke))

        assertTrue(history.canUndo)
        assertFalse(history.canRedo)
        assertEquals(1, doc.pages[0].strokes.size)

        // Undo
        history.undo(context)
        testScheduler.advanceUntilIdle()

        assertEquals(0, doc.pages[0].strokes.size)
        assertFalse(history.canUndo)
        assertTrue(history.canRedo)

        // Redo
        history.redo(context)
        testScheduler.advanceUntilIdle()

        assertEquals(1, doc.pages[0].strokes.size)
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)
    }
}
