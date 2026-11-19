package io.github.valerioisufi.pageboard.canvas

import androidx.compose.ui.geometry.Rect as ComposeRect
import io.github.valerioisufi.pageboard.canvas.camera.CalcPage
import io.github.valerioisufi.pageboard.canvas.camera.CameraPhysicsEngine
import io.github.valerioisufi.pageboard.canvas.history.*
import io.github.valerioisufi.pageboard.canvas.tool.*
import io.github.valerioisufi.pageboard.model.*
import io.github.valerioisufi.pageboard.model.change.AddElements
import io.github.valerioisufi.pageboard.model.change.DocumentSession
import io.github.valerioisufi.pageboard.model.change.RemoveElements
import io.github.valerioisufi.pageboard.model.geometry.*
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
        val doc = Document()
        doc.addPage() // page 1
        doc.addPage() // page 2

        assertEquals(3, doc.pages.size)

        val windowRect = ComposeRect(0f, 0f, 800f, 1200f)
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
        var content = ComposeRect(0f, 0f, 800f, 2000f)
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
        assertEquals(40f, finalPos.y, 1e-3f)
    }

    @Test
    fun testCameraPinchBeyondTheZoomLimits() {
        val engine = CameraPhysicsEngine(density = 1f, getContentRect = { ComposeRect(0f, 0f, 800f, 2000f) })
        engine.setViewport(800f, 1000f)

        // A zoom apart from a gesture (zoom buttons, wheel) stops at the limit
        engine.onDrag(dx = 0f, dy = 0f, scaleFactor = 1000f, focusX = 400f, focusY = 500f)
        assertEquals(engine.maxScale, engine.getCurrentScale())

        // A pinch goes beyond it with a rubber band...
        engine.onDragStart()
        repeat(20) { engine.onDrag(dx = 0f, dy = 0f, scaleFactor = 1.3f, focusX = 400f, focusY = 500f) }
        val stretched = engine.getCurrentScale()
        assertTrue(stretched > engine.maxScale && stretched < engine.maxScale * engine.maxScaleOverscroll)
        // ...and moving back the fingers shrinks it at once
        engine.onDrag(dx = 0f, dy = 0f, scaleFactor = 0.9f, focusX = 400f, focusY = 500f)
        assertTrue(engine.getCurrentScale() < stretched)

        // Released: it springs back into the limits
        engine.onRelease(0f, 0f)
        assertTrue(engine.isAnimating())
        repeat(200) { engine.update(16L) }
        assertFalse(engine.isAnimating())
        assertTrue(engine.getCurrentScale() <= engine.maxScale && engine.getCurrentScale() > engine.maxScale * 0.9f)
    }

    @Test
    fun testCameraTellsWhenAnAnimationStarts() {
        val engine = CameraPhysicsEngine(density = 1f, getContentRect = { ComposeRect(0f, 0f, 800f, 2000f) })
        engine.setViewport(800f, 1000f)

        // Released at rest, inside the edges: nothing moves by itself, the canvas asks for no frame
        engine.onDragStart()
        engine.onDrag(dx = 0f, dy = -100f, scaleFactor = 1f, focusX = 400f, focusY = 500f)
        engine.onRelease(0f, 0f)
        engine.restoreToBounds()
        assertEquals(0, engine.animationStarts)

        // A fling starts an animation
        engine.onDragStart()
        engine.onRelease(0f, -3000f)
        assertTrue(engine.isAnimating())
        assertEquals(1, engine.animationStarts)

        // Beyond the edges, the bounce back starts one too
        val pulled = CameraPhysicsEngine(density = 1f, getContentRect = { ComposeRect(0f, 0f, 800f, 2000f) })
        pulled.setViewport(800f, 1000f)
        pulled.onDragStart()
        pulled.onDrag(dx = 0f, dy = 200f, scaleFactor = 1f, focusX = 400f, focusY = 500f)
        pulled.onRelease(0f, 0f)
        assertTrue(pulled.isAnimating())
        assertEquals(1, pulled.animationStarts)
    }

    // --- 5. HISTORY MANAGER (UNDO/REDO) TESTS ---

    @Test
    fun testHistoryManagerUndoRedo() {
        val doc = Document()
        val session = DocumentSession(doc)
        val history = HistoryManager(session, maxHistorySize = 10)

        val dummyMesh = StrokeMesh(
            positions = floatArrayOf(0f, 0f, 10f, 10f),
            indices = shortArrayOf(0, 1, 2),
            color = 0xFF000000.toInt(),
            tool = StrokeTool.PRESSURE_PEN,
            size = 5f
        )
        val stroke = StrokeElement(mesh = dummyMesh, brushConfig = BrushConfig())

        // Add stroke
        session.submit(AddElements.of(doc.pages[0], listOf(stroke)))

        assertTrue(history.canUndo)
        assertFalse(history.canRedo)
        assertEquals(1, doc.pages[0].strokes.size)

        // Undo
        history.undo()
        assertEquals(0, doc.pages[0].strokes.size)
        assertFalse(history.canUndo)
        assertTrue(history.canRedo)

        // Redo: the same stroke
        history.redo()
        assertEquals(listOf(stroke), doc.pages[0].strokes.toList())
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)

        // Removed by another device: undoing its addition changes nothing
        session.receive(remoteOperation(RemoveElements.of(doc.pages[0], listOf(stroke))))
        history.undo()
        assertTrue(doc.pages[0].strokes.isEmpty())
    }
}
