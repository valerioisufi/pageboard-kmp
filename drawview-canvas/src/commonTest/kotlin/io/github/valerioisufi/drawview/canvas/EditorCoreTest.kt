package io.github.valerioisufi.drawview.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.valerioisufi.drawview.canvas.controller.DrawPersistence
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.canvas.controller.PersistenceQueue
import io.github.valerioisufi.drawview.canvas.controller.PlacementRequest
import io.github.valerioisufi.drawview.canvas.geometry.toCompose
import io.github.valerioisufi.drawview.canvas.tool.Tool
import io.github.valerioisufi.drawview.model.BrushConfig
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.DrawStroke
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.InputToolType
import io.github.valerioisufi.drawview.model.KotlinStrokeEngineDelegate
import io.github.valerioisufi.drawview.model.Stamp
import io.github.valerioisufi.drawview.model.StrokeMesh
import io.github.valerioisufi.drawview.model.StrokePoint
import io.github.valerioisufi.drawview.model.StrokeTool
import io.github.valerioisufi.drawview.model.change.ChangeOrigin
import io.github.valerioisufi.drawview.model.change.DocumentChange
import io.github.valerioisufi.drawview.model.change.DocumentSession
import io.github.valerioisufi.drawview.model.change.Operation
import io.github.valerioisufi.drawview.model.change.OperationId
import io.github.valerioisufi.drawview.model.change.RemoveElements
import io.github.valerioisufi.drawview.model.geometry.HitTesting
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import io.github.valerioisufi.drawview.model.geometry.Rect as MmRect
import io.github.valerioisufi.drawview.platform.currentTimeMillis
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

@OptIn(ExperimentalCoroutinesApi::class)
class EditorCoreTest {

    private fun squareMesh(left: Float, top: Float, size: Float): StrokeMesh = StrokeMesh(
        positions = floatArrayOf(left, top, left + size, top, left + size, top + size, left, top + size),
        indices = shortArrayOf(0, 1, 2, 0, 2, 3),
        color = Color.Black.toArgb(),
        tool = StrokeTool.MARKER,
        size = 1f,
        points = listOf(StrokePoint(left, top), StrokePoint(left + size, top + size))
    )

    private fun stroke(mesh: StrokeMesh) = DrawStroke(mesh = mesh, brushConfig = BrushConfig(size = 1f))

    // --- Engine ---

    @Test
    fun kotlinTessellatorProducesClosedStrokeMesh() {
        val delegate = KotlinStrokeEngineDelegate()
        val brush = BrushConfig(tool = StrokeTool.PRESSURE_PEN, size = 1f, color = Color.Black.toArgb(), epsilon = 0.01f)
        delegate.start(brush, 0.1f)
        for (i in 0..20) delegate.addInput(10f + i, 10f + i * 0.5f, 0.5f, i * 16L, InputToolType.STYLUS)
        val mesh = assertNotNull(delegate.currentMesh(brush, emptyList(), InputToolType.STYLUS))
        assertTrue(mesh.vertexCount > 40)
        assertEquals(0, mesh.indices.size % 3)
        val bounds = mesh.computeBounds()
        assertTrue(bounds.left < 10f && bounds.right > 30f)
    }

    @Test
    fun kotlinTessellatorDashesTheLasso() {
        val delegate = KotlinStrokeEngineDelegate()
        val brush = BrushConfig(tool = StrokeTool.LASSO, size = 0.5f, color = Color.Gray.toArgb(), epsilon = 0.01f)
        delegate.start(brush, 0.1f)
        for (i in 0..200) delegate.addInput(i * 0.5f, 0f, -1f, i * 8L, InputToolType.MOUSE)
        val mesh = assertNotNull(delegate.currentMesh(brush, emptyList(), InputToolType.MOUSE))
        // A continuous strip would contain a vertex on every sample: dashes leave gaps
        val xs = (0 until mesh.vertexCount).map { mesh.positions[it * 2] }.sorted()
        val gaps = xs.zipWithNext().count { (a, b) -> b - a > 1f }
        assertTrue(gaps >= 3, "expected gaps between dashes, got $gaps")
    }

    @Test
    fun largeMeshesArePartitionedFor16BitIndices() {
        val vertexCount = 70_000
        val positions = FloatArray(vertexCount * 2) { it.toFloat() }
        val indices = IntArray((vertexCount - 2) * 3) { i ->
            val t = i / 3
            t + i % 3
        }
        val mesh = StrokeMesh.fromIndexedMesh(positions, indices, Color.Black.toArgb(), StrokeTool.MARKER, 1f, emptyList(), InputToolType.UNKNOWN)
        assertTrue(mesh.partitions.size >= 2)
        assertEquals(vertexCount - 2, mesh.triangleCount)
        for (partition in mesh.partitions) {
            assertTrue(partition.vertexCount <= 65535)
            for (index in partition.indices) {
                assertTrue((index.toInt() and 0xFFFF) < partition.vertexCount)
            }
        }
    }

    // --- Geometry ---

    @Test
    fun matrixRotationAndRectMapping() {
        val m = Matrix2D().postRotate(90f, 10f, 10f)
        val p = m.mapPoint(20f, 10f).toCompose()
        assertEquals(10f, p.x, 1e-4f)
        assertEquals(20f, p.y, 1e-4f)
        assertEquals(90f, m.rotationDegrees, 1e-3f)

        val r = Matrix2D().setRectToRect(MmRect(0f, 0f, 210f, 297f), MmRect(100f, 50f, 520f, 644f))
        val corner = r.mapPoint(210f, 297f).toCompose()
        assertEquals(520f, corner.x, 1e-3f)
        assertEquals(644f, corner.y, 1e-3f)

        val a = Matrix2D().postScale(2f, 2f)
        val b = Matrix2D().postTranslate(5f, 0f)
        val pre = Matrix2D(a).preConcat(b) // a * b: translate first, then scale
        assertEquals(10f, pre.mapPoint(0f, 0f).toCompose().x, 1e-4f)
    }

    @Test
    fun hitTestingCapsuleAndPolygon() {
        val mesh = squareMesh(10f, 10f, 10f)
        assertTrue(HitTesting.meshIntersectsCapsule(mesh, 0f, 15f, 30f, 15f, 0.5f))
        assertTrue(HitTesting.meshIntersectsCapsule(mesh, 0f, 0f, 9.5f, 9.5f, 1f))
        assertFalse(HitTesting.meshIntersectsCapsule(mesh, 0f, 0f, 5f, 5f, 1f))

        val lasso = floatArrayOf(5f, 5f, 25f, 5f, 25f, 25f, 5f, 25f)
        assertTrue(HitTesting.meshIntersectsPolygon(mesh, lasso, HitTesting.polygonBounds(lasso)))
        val farLasso = floatArrayOf(50f, 50f, 60f, 50f, 60f, 60f)
        assertFalse(HitTesting.meshIntersectsPolygon(mesh, farLasso, HitTesting.polygonBounds(farLasso)))
        assertTrue(HitTesting.pointInPolygon(15f, 15f, lasso))
    }

    // --- History ---

    @Test
    fun eraseUndoRestoresTheOriginalStackingOrder() = runTest {
        val doc = DrawDocument()
        val session = DocumentSession(doc)
        val page = doc.pages[0]
        val strokes = List(4) { stroke(squareMesh(it * 20f, 0f, 5f)) }
        strokes.forEach { page.add(it) }

        // Erase B then D: undo puts the same strokes back where they were
        val undo = assertNotNull(session.submit(RemoveElements.of(page, listOf(strokes[1], strokes[3]))))
        assertEquals(listOf(strokes[0], strokes[2]), page.strokes.toList())
        val redo = assertNotNull(session.submit(undo, ChangeOrigin.UNDO))
        assertEquals(strokes, page.strokes.toList())
        session.submit(redo, ChangeOrigin.REDO)
        assertEquals(listOf(strokes[0], strokes[2]), page.strokes.toList())
    }

    // --- Persistence queue ---

    @Test
    fun persistenceOperationsRunInSubmissionOrder() = runTest(UnconfinedTestDispatcher()) {
        val calls = mutableListOf<String>()
        val persistence = object : DrawPersistence {
            override suspend fun saveStrokes(page: DrawPage, strokes: List<DrawStroke>) {
                yield()
                calls.add("save:${strokes.first().id}")
            }

            override suspend fun touchDocument(document: DrawDocument, timestamp: Long) {
                calls.add("touch")
            }
        }
        val queue = PersistenceQueue(backgroundScope, persistence)
        val s = stroke(squareMesh(0f, 0f, 1f))
        queue.enqueue { saveStrokes(DrawPage(), listOf(s)) }
        queue.enqueue { touchDocument(DrawDocument(), 0L) }
        queue.flush()
        assertEquals(listOf("save:${s.id}", "touch"), calls)
    }

    // --- Controller ---

    private fun controllerWith(pages: Int, scope: kotlinx.coroutines.CoroutineScope): DrawViewController {
        val doc = DrawDocument()
        repeat(pages - 1) { doc.addPage() }
        val controller = DrawViewController(document = doc, coroutineScope = scope)
        controller.setViewport(800f, 1200f)
        return controller
    }

    @Test
    fun theCameraShowsThePagesOneByOne() = runTest(UnconfinedTestDispatcher()) {
        val controller = controllerWith(3, backgroundScope)
        assertEquals(0, controller.mostVisiblePageIndex())

        controller.showNextPage()
        assertEquals(1, controller.mostVisiblePageIndex())
        // The top of the page under the top margin
        val top = controller.visiblePages().single { it.index == 1 }.rect.top
        assertEquals(controller.cameraPhysics.topPaddingPx, top, 1f)

        controller.showNextPage()
        controller.showNextPage()
        assertEquals(2, controller.mostVisiblePageIndex())
        controller.showPreviousPage()
        assertEquals(1, controller.mostVisiblePageIndex())

        // Zoomed in, then the width of the pages again, from the page looked at
        controller.zoomIn()
        controller.zoomIn()
        assertTrue(controller.cameraPhysics.getCurrentScale() > 1.4f)
        controller.fitWidth()
        assertEquals(1f, controller.cameraPhysics.getCurrentScale(), 0.001f)
        assertEquals(1, controller.mostVisiblePageIndex())
    }

    @Test
    fun strokesCrossingPagesAreDuplicatedOnEveryPage() = runTest(UnconfinedTestDispatcher()) {
        val controller = controllerWith(2, backgroundScope)
        val page0 = controller.document.pages[0]
        // A stroke from the bottom of page 0 to below its bottom edge (into page 1)
        val mesh = StrokeMesh(
            positions = floatArrayOf(100f, 290f, 101f, 290f, 101f, 310f, 100f, 310f),
            indices = shortArrayOf(0, 1, 2, 0, 2, 3),
            color = Color.Black.toArgb(),
            tool = StrokeTool.PRESSURE_PEN,
            size = 1f,
            points = listOf(StrokePoint(100.5f, 290f), StrokePoint(100.5f, 310f))
        )
        controller.commitInkStroke(mesh, 0, BrushConfig(size = 1f))
        assertEquals(1, page0.strokes.size)
        val page1 = controller.document.pages[1]
        assertEquals(1, page1.strokes.size)
        // The copy on page 1 is expressed in the millimeters of page 1 (above its top edge)
        assertTrue(page1.strokes[0].bounds.top < 0f)
        assertTrue(controller.canUndo)

        controller.undo()
        assertEquals(0, page0.strokes.size)
        assertEquals(0, page1.strokes.size)
    }

    @Test
    fun eraserRemovesOnlyTheStrokesUnderItsPath() = runTest(UnconfinedTestDispatcher()) {
        val controller = controllerWith(1, backgroundScope)
        controller.selectTool(Tool.ERASER)
        val page = controller.document.pages[0]
        val hit = stroke(squareMesh(50f, 50f, 5f))
        val miss = stroke(squareMesh(150f, 200f, 5f))
        page.add(hit)
        page.add(miss)

        val mmToScreen = assertNotNull(controller.pageMmToScreen(0))
        val from = mmToScreen.mapPoint(40f, 52f).toCompose()
        val to = mmToScreen.mapPoint(70f, 52f).toCompose()
        val erased = controller.eraserManager.eraseAlongSegment(from, to)
        controller.eraserManager.commitGesture()

        assertEquals(1, erased)
        assertEquals(listOf(miss), page.strokes.toList())
        controller.undo()
        assertEquals(listOf(hit, miss), page.strokes.toList())
    }

    @Test
    fun pendingPlacementIsPlacedInsideTheTappedPage() = runTest(UnconfinedTestDispatcher()) {
        val controller = controllerWith(2, backgroundScope)
        val placement = controller.placementManager
        val placed = mutableListOf<Triple<Int, Float, Float>>()
        var cancelled = 0
        fun request() = PlacementRequest(
            preview = DrawText().apply {
                width = 50f
                height = 20f
            },
            onPlace = { pageIndex, x, y -> placed.add(Triple(pageIndex, x, y)) },
            onCancel = { cancelled++ }
        )

        placement.start(request())
        assertTrue(placement.isActive)
        val mmToScreen = assertNotNull(controller.pageMmToScreen(0))
        // Near the bottom-right corner of the A4 page: the element stays inside it
        val (pageIndex, topLeft) = assertNotNull(placement.targetAt(mmToScreen.mapPoint(200f, 290f).toCompose()))
        assertEquals(0, pageIndex)
        assertEquals(160f, topLeft.x, 0.01f)
        assertEquals(277f, topLeft.y, 0.01f)
        // Outside of the pages the placement stays pending
        assertNull(placement.targetAt(Offset(-50f, -50f)))
        assertFalse(placement.placeAt(Offset(-50f, -50f)))
        assertTrue(placement.isActive)

        assertTrue(placement.placeAt(mmToScreen.mapPoint(20f, 30f).toCompose()))
        val (placedPage, x, y) = placed.single()
        assertEquals(0, placedPage)
        assertEquals(20f, x, 0.01f)
        assertEquals(30f, y, 0.01f)
        assertFalse(placement.isActive)
        assertEquals(0, cancelled)

        // A new request cancels the pending one
        placement.start(request())
        placement.start(request())
        assertEquals(1, cancelled)
        placement.cancel()
        assertEquals(2, cancelled)
        assertFalse(placement.isActive)
    }

    @Test
    fun lassoSelectionMoveAndUndo() = runTest(UnconfinedTestDispatcher()) {
        val controller = controllerWith(1, backgroundScope)
        val page = controller.document.pages[0]
        val target = stroke(squareMesh(20f, 20f, 10f))
        val other = stroke(squareMesh(150f, 150f, 10f))
        page.add(target)
        page.add(other)

        controller.selectionManager.selectWithLasso(0, floatArrayOf(10f, 10f, 40f, 10f, 40f, 40f, 10f, 40f))
        val selection = assertNotNull(controller.selectionManager.currentSelection)
        assertEquals(listOf(target), selection.strokes.toList())
        assertTrue(controller.hiddenElements.isDragged(target.id))

        controller.selectionManager.beginTransformation()
        controller.selectionManager.translateBy(30f, 5f)
        controller.selectionManager.applyTransformation()
        assertEquals(50f, target.bounds.left, 1e-3f)
        assertEquals(25f, target.bounds.top, 1e-3f)
        assertEquals(50f, target.mesh.points.first().x, 1e-3f)

        controller.selectionManager.clearSelection()
        assertFalse(controller.hiddenElements.isDragged(target.id))
        controller.undo()
        assertEquals(20f, target.bounds.left, 1e-3f)
        assertSame(page, controller.document.pages[0])
        assertTrue(page.strokes.contains(target))
    }

    @Test
    fun pagesCanBeAddedDeletedAndRestored() = runTest(UnconfinedTestDispatcher()) {
        val controller = controllerWith(1, backgroundScope)
        val first = controller.document.pages[0]
        val added = controller.pageManager.addNewPageAfter(0)
        assertEquals(2, controller.document.pages.size)
        assertSame(added, controller.document.pages[1])

        controller.pageManager.deletePage(0)
        assertEquals(listOf(added), controller.document.pages.toList())
        assertEquals(0, added.index)

        controller.undo()
        assertEquals(listOf(first, added), controller.document.pages.toList())
        controller.undo()
        assertEquals(listOf(first), controller.document.pages.toList())
        assertTrue(abs(controller.calcPage.pagesRectOnWindow.size - 1) == 0)
    }

    @Test
    fun screenAndPageCoordinatesRoundTrip() = runTest(UnconfinedTestDispatcher()) {
        val controller = controllerWith(2, backgroundScope)
        val screen = assertNotNull(controller.pageMmToScreen(1)).mapPoint(105f, 148.5f).toCompose()
        assertEquals(1, controller.findPageIndexAtScreen(screen) ?: controller.findPageIndexAtWorld(controller.screenToWorld(screen)))
        val back = assertNotNull(controller.screenToPageMm(1, screen))
        assertEquals(105f, back.x, 1e-2f)
        assertEquals(148.5f, back.y, 1e-2f)
        assertTrue(controller.pixelsPerMm(0) > 0f)
        assertEquals(Offset(0f, 0f), controller.screenToPageMm(0, controller.pageMmToScreen(0)!!.mapPoint(0f, 0f).toCompose())!!.let {
            Offset(if (abs(it.x) < 1e-3f) 0f else it.x, if (abs(it.y) < 1e-3f) 0f else it.y)
        })
    }
}

/** [change] as an operation of another device, made after everything this one has seen. */
internal fun remoteOperation(change: DocumentChange, sequence: Long = 1) =
    Operation(OperationId("other-device", sequence), Stamp(currentTimeMillis() + 60_000, 0, "other-device"), change)
