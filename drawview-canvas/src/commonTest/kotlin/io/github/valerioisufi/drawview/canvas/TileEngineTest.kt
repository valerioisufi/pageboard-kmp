package io.github.valerioisufi.drawview.canvas

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import io.github.valerioisufi.drawview.canvas.render.tile.*
import io.github.valerioisufi.drawview.canvas.tool.*
import io.github.valerioisufi.drawview.model.*
import kotlin.test.*

class TileEngineTest {

    @Test
    fun testLodCalculations() {
        assertEquals(0, TilePyramid.calculateLod(1.0f))
        assertEquals(1, TilePyramid.calculateLod(2.0f))
        assertEquals(2, TilePyramid.calculateLod(4.0f))
        assertEquals(-1, TilePyramid.calculateLod(0.5f))
        assertEquals(-2, TilePyramid.calculateLod(0.25f))

        // Clamping to MIN_LOD and MAX_LOD
        assertEquals(TilePyramid.MAX_LOD, TilePyramid.calculateLod(100f))
        assertEquals(TilePyramid.MIN_LOD, TilePyramid.calculateLod(0.01f))
    }

    @Test
    fun testTilePyramidSizes() {
        assertEquals(512f, TilePyramid.getTileWorldSize(0))
        assertEquals(256f, TilePyramid.getTileWorldSize(1))
        assertEquals(1024f, TilePyramid.getTileWorldSize(-1))
    }

    @Test
    fun testTileKeyHierarchy() {
        val child = TileKey(pageKey = 0L, lod = 1, col = 3, row = 2)
        val parent = child.getParentKey()

        assertEquals(0, parent.lod)
        assertEquals(1, parent.col)
        assertEquals(1, parent.row)

        val children = parent.getChildrenKeys()
        assertEquals(4, children.size)
        assertTrue(children.contains(TileKey(0L, 1, 2, 2)))
        assertTrue(children.contains(TileKey(0L, 1, 3, 2)))
        assertTrue(children.contains(TileKey(0L, 1, 2, 3)))
        assertTrue(children.contains(TileKey(0L, 1, 3, 3)))
    }

    @Test
    fun testTileCacheLruAndAncestorLookup() {
        val cache = TileCache(maxMemoryBytes = 4 * 512 * 512 * 4) // max 4 tiles

        val parentKey = TileKey(0L, 0, 0, 0)
        val childKey = TileKey(0L, 1, 0, 0)

        val parentTile = Tile(parentKey, cache.obtainBitmap(), TileState.READY)
        cache.put(parentTile)

        // Lookup before child exists: ancestor should be found
        val foundAncestor = cache.findNearestAncestor(childKey)
        assertNotNull(foundAncestor)
        assertEquals(parentKey, foundAncestor.key)

        // Put child
        val childTile = Tile(childKey, cache.obtainBitmap(), TileState.READY)
        cache.put(childTile)
        assertTrue(cache.contains(childKey))

        // Descendants lookup from parent
        val descendants = cache.findReadyDescendants(parentKey)
        assertTrue(descendants.any { it.key == childKey })

        // Invalidation to STALE
        cache.invalidate { it == childKey }
        assertEquals(TileState.STALE, cache.get(childKey)?.state)
    }

    @Test
    fun testTileRendererWithPageBackground() {
        val renderer = TileRenderer()
        val cache = TileCache()
        val bitmap = cache.obtainBitmap()

        val doc = DrawDocument("test_doc", "Test Document")
        val page = doc.pages[0]
        page.background = PageBackground.Grid(spacingMm = 5f)

        val key = TileKey(pageKey = page.renderKey, lod = 0, col = 0, row = 0)
        val pageRect = Rect(0f, 0f, 210f, 297f)

        // Render should execute cleanly without throwing
        renderer.renderTile(
            tileKey = key,
            targetBitmap = bitmap,
            document = doc,
            page = page,
            pageWorldRect = pageRect
        )

        assertEquals(512, bitmap.width)
        assertEquals(512, bitmap.height)
    }

    @Test
    fun aTileDrawsTheLinesOfATextBelowItsOldBox() {
        // Every text laid out 40 mm high; the text says 5 mm (a height of an older layout)
        val drawn = mutableListOf<String>()
        val renderer = TileRenderer(textAdapter = object : TextTileAdapter {
            override fun drawText(canvas: androidx.compose.ui.graphics.Canvas, text: DrawText, mmToCanvas: io.github.valerioisufi.drawview.model.geometry.Matrix2D, paperColor: Int) {
                drawn += text.id
            }

            override fun measure(text: DrawText) = TextBlockSize(text.width, 40f)
        })
        val doc = DrawDocument("test_doc", "Test Document")
        val page = doc.pages[0]
        val text = DrawText().apply { this.text = "lines"; x = 10f; y = 10f; width = 50f; height = 5f }
        page.add(text)

        // 1 px per mm, the region from 30 mm down: under the old box, over the lines
        val canvas = androidx.compose.ui.graphics.Canvas(ImageBitmap(210, 297))
        val mmToCanvas = io.github.valerioisufi.drawview.model.geometry.Matrix2D()
        renderer.renderPageContent(canvas, doc, page.snapshotContent(), mmToCanvas, Rect(0f, 30f, 210f, 297f))
        assertEquals(listOf(text.id), drawn)

        drawn.clear()
        renderer.renderPageContent(canvas, doc, page.snapshotContent(), mmToCanvas, Rect(0f, 60f, 210f, 297f))
        assertTrue(drawn.isEmpty(), "below its lines")
    }

    @Test
    fun testSynchronousBaking() {
        val manager = TileManager()
        val doc = DrawDocument("test_doc", "Test Document")
        val page = doc.pages[0]

        val key = TileKey(pageKey = page.renderKey, lod = 0, col = 0, row = 0)
        val tile = Tile(key, manager.tileCache.obtainBitmap(), TileState.READY)
        manager.tileCache.put(tile)

        val pageRects = listOf(Rect(0f, 0f, 500f, 700f))

        // Create a dummy stroke in page bounds
        val mesh = StrokeMesh(
            positions = floatArrayOf(50f, 50f, 60f, 60f, 70f, 50f),
            indices = shortArrayOf(0, 1, 2),
            color = 0xFF000000.toInt(),
            tool = StrokeTool.PRESSURE_PEN,
            size = 2f,
            points = emptyList()
        )
        val stroke = DrawStroke(mesh = mesh, brushConfig = BrushConfig())

        manager.bakeStrokesDirectlySync(
            strokesByPage = mapOf(0 to listOf(stroke)),
            document = doc,
            pageRects = pageRects,
            cameraScale = 1.0f
        )

        // Tile should still be READY and in cache
        val retrieved = manager.tileCache.get(key)
        assertNotNull(retrieved)
        assertEquals(TileState.READY, retrieved.state)

        manager.cleanup()
    }

    @Test
    fun testTilesDroppedWhenPageGeometryChanges() {
        val manager = TileManager()
        val doc = DrawDocument("test_doc", "Test Document")
        val page = doc.pages[0]
        val canvas = androidx.compose.ui.graphics.Canvas(ImageBitmap(100, 100))
        val camera = io.github.valerioisufi.drawview.model.geometry.Matrix2D()
        // Viewport far from the page: no background rasterization interferes with the test
        val viewport = Rect(5000f, 5000f, 5100f, 5100f)

        manager.drawTiles(canvas, camera, viewport, doc, listOf(Rect(0f, 0f, 500f, 700f)))
        val key = TileKey(pageKey = page.renderKey, lod = 0, col = 0, row = 0)
        val oldTile = Tile(key, manager.tileCache.obtainBitmap(), TileState.READY)
        manager.tileCache.put(oldTile)

        // Same geometry: the tile is kept
        manager.drawTiles(canvas, camera, viewport, doc, listOf(Rect(0f, 0f, 500f, 700f)))
        assertSame(oldTile, manager.tileCache.get(key))

        // The window got wider: the page is laid out at another scale
        manager.drawTiles(canvas, camera, viewport, doc, listOf(Rect(0f, 0f, 520f, 728f)))
        assertNull(manager.tileCache.get(key))

        manager.cleanup()
    }

    @Test
    fun testTilesOnScreenDrawnStretchedUntilTheNewLayoutIsReady() {
        val manager = TileManager()
        val doc = DrawDocument("test_doc", "Test Document")
        val page = doc.pages[0]
        val camera = io.github.valerioisufi.drawview.model.geometry.Matrix2D()
        val viewport = Rect(0f, 0f, 200f, 200f)

        val key = TileKey(pageKey = page.renderKey, lod = 0, col = 0, row = 0)
        val bitmap = manager.tileCache.obtainBitmap()
        androidx.compose.ui.graphics.Canvas(bitmap).drawRect(
            Rect(0f, 0f, 512f, 512f),
            androidx.compose.ui.graphics.Paint().apply { color = Color.Red }
        )
        manager.tileCache.put(Tile(key, bitmap, TileState.READY))
        manager.drawTiles(androidx.compose.ui.graphics.Canvas(ImageBitmap(200, 200)), camera, viewport, doc, listOf(Rect(0f, 0f, 500f, 700f)))

        // The window got wider: the tile is rasterized again, the old one is drawn meanwhile
        manager.updateLayout(doc.pages, listOf(Rect(0f, 0f, 520f, 728f)))
        assertNull(manager.tileCache.get(key))
        val screen = ImageBitmap(200, 200)
        manager.drawTiles(androidx.compose.ui.graphics.Canvas(screen), camera, viewport, doc, listOf(Rect(0f, 0f, 520f, 728f)))
        assertEquals(Color.Red, screen.toPixelMap()[100, 100])

        manager.cleanup()
    }

    @Test
    fun testPriorityEvictionPreservesBaseLodTiles() {
        // Cache with budget of 4 tiles (4 MB)
        val cache = TileCache(maxMemoryBytes = 4 * 512 * 512 * 4)

        val baseKey1 = TileKey(0L, 0, 0, 0)
        val baseKey2 = TileKey(0L, 0, 1, 0)
        cache.put(Tile(baseKey1, cache.obtainBitmap(), TileState.READY))
        cache.put(Tile(baseKey2, cache.obtainBitmap(), TileState.READY))

        // Advance frames so base tiles become older than frame - 1
        cache.beginFrame()
        cache.beginFrame()

        val detailKey1 = TileKey(0L, 1, 0, 0)
        val detailKey2 = TileKey(0L, 1, 1, 0)
        cache.put(Tile(detailKey1, cache.obtainBitmap(), TileState.READY))
        cache.put(Tile(detailKey2, cache.obtainBitmap(), TileState.READY))

        // Total 4 tiles, right at budget limit
        assertEquals(4, cache.entryCount)
        assertTrue(cache.contains(baseKey1))
        assertTrue(cache.contains(baseKey2))

        // Advance frames so detail tiles at LOD 1 also become eligible for eviction
        cache.beginFrame()
        cache.beginFrame()

        // Insert high detail tiles at LOD 2, exceeding the budget
        val deepDetailKey1 = TileKey(0L, 2, 0, 0)
        val deepDetailKey2 = TileKey(0L, 2, 1, 0)
        cache.put(Tile(deepDetailKey1, cache.obtainBitmap(), TileState.READY))
        cache.put(Tile(deepDetailKey2, cache.obtainBitmap(), TileState.READY))

        // Budget is 4 tiles: the 2 detail tiles at LOD 1 must be evicted first,
        // while the 2 base tiles at LOD 0 must be preserved.
        assertEquals(4, cache.entryCount)
        assertTrue(cache.contains(baseKey1), "Base LOD 0 tile 1 must be preserved")
        assertTrue(cache.contains(baseKey2), "Base LOD 0 tile 2 must be preserved")
        assertFalse(cache.contains(detailKey1), "Detail LOD 1 tile 1 should be evicted")
        assertFalse(cache.contains(detailKey2), "Detail LOD 1 tile 2 should be evicted")
        assertTrue(cache.contains(deepDetailKey1), "Recent LOD 2 tile 1 should be present")
        assertTrue(cache.contains(deepDetailKey2), "Recent LOD 2 tile 2 should be present")
    }

    @Test
    fun overviewsSurviveACacheFullOfTilesInUse() {
        val cache = TileCache(maxMemoryBytes = 4 * 512 * 512 * 4)
        val overview = TileKey(0L, TilePyramid.OVERVIEW_LOD, 0, 0)
        val base = TileKey(0L, 0, 0, 0)
        cache.put(Tile(overview, cache.obtainBitmap(), TileState.READY))
        cache.put(Tile(base, cache.obtainBitmap(), TileState.READY))

        // Zoomed in: the tiles of a frame alone are beyond the budget
        repeat(2) { cache.beginFrame() }
        for (col in 0 until 6) {
            cache.put(Tile(TileKey(0L, 4, col, 0), cache.obtainBitmap(), TileState.READY))
        }

        assertFalse(cache.contains(base))
        assertTrue(cache.contains(overview))
        // What the zoom out finds, from any cell of the page
        assertEquals(overview, cache.findNearestAncestor(TileKey(0L, 3, 7, 7))?.key)
    }

    @Test
    fun overviewsStayWithinTheirBudgetTheOldestLeavingFirst() {
        val cache = TileCache()
        fun overviewOf(page: Int) = TileKey(page.toLong(), TilePyramid.OVERVIEW_LOD, 0, 0)
        repeat(20) { page ->
            repeat(2) { cache.beginFrame() }
            cache.put(Tile(overviewOf(page), cache.obtainBitmap(), TileState.READY))
        }

        assertEquals(16, cache.entryCount)
        assertFalse(cache.contains(overviewOf(3)))
        assertTrue(cache.contains(overviewOf(4)))
    }

    @Test
    fun aStaleAncestorIsDrawnRatherThanNothing() {
        val cache = TileCache()
        val overview = TileKey(0L, TilePyramid.OVERVIEW_LOD, 0, 0)
        cache.put(Tile(overview, cache.obtainBitmap(), TileState.STALE))
        assertEquals(overview, cache.findNearestAncestor(TileKey(0L, 2, 1, 1))?.key)
    }
}
