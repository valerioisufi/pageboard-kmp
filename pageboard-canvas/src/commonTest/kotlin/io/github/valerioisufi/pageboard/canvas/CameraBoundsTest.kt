package io.github.valerioisufi.pageboard.canvas

import androidx.compose.ui.geometry.Rect
import io.github.valerioisufi.pageboard.canvas.camera.CalcPage
import io.github.valerioisufi.pageboard.canvas.camera.CameraPhysicsEngine
import io.github.valerioisufi.pageboard.model.Page
import io.github.valerioisufi.pageboard.model.geometry.Matrix2D
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The bounds of the camera (`docs/plans/camera-bounds.md`): pages narrower than the canvas stay
 * centered, wider pages stop at their edges, and the bounds change continuously with the zoom.
 */
class CameraBoundsTest {

    /** A canvas [width] × [height] pixels at [density] showing [pages], at the zoom of the opening. */
    private class Canvas(
        val width: Float,
        val density: Float,
        pages: List<Page> = listOf(Page()),
        val height: Float = 900f * density
    ) {
        val layout = CalcPage(density = density).apply {
            calcPagesRectOnWindow(pages, Rect(0f, 0f, width, height))
        }
        val camera = CameraPhysicsEngine(density = density, getContentRect = { layout.contentRect }).apply {
            setViewport(width, height)
            centerOnWorldPoint(width / 2f, height / 2f, 1f, width, height)
            restoreToBounds(animated = false)
        }

        /** The margin beside the pages at the zoom of the opening, in pixels. */
        val margin = 8f * density

        fun zoomTo(scale: Float) {
            camera.centerOnWorldPoint(width / 2f, height / 2f, scale, width, height)
            camera.restoreToBounds(animated = false)
        }

        /** Pans by [dx] as far as the bounds let it, and returns where the page [index] is then. */
        fun panBy(dx: Float, index: Int = 0): Rect {
            camera.onDrag(dx, 0f)
            camera.restoreToBounds(animated = false)
            return pageOnScreen(index)
        }

        fun pageOnScreen(index: Int = 0): Rect {
            val scale = camera.getCurrentScale()
            val translation = camera.getCurrentTranslation()
            val page = layout.pagesRectOnWindow[index]
            return Rect(
                page.left * scale + translation.x,
                page.top * scale + translation.y,
                page.right * scale + translation.x,
                page.bottom * scale + translation.y
            )
        }

        fun settle() {
            repeat(300) { camera.update(16L) }
            assertFalse(camera.isAnimating())
        }
    }

    /** A desktop window of 1440 dp at 2×: the pages are 880 dp wide, the desk beside them. */
    private fun wideWindow(pages: List<Page> = listOf(Page())) = Canvas(width = 2880f, density = 2f, pages = pages)

    @Test
    fun aPageNarrowerThanAWideWindowStaysCentered() {
        val canvas = wideWindow()
        for (scale in listOf(1f, 1.25f, 1.5f)) {
            canvas.zoomTo(scale)
            for (dx in listOf(100_000f, -100_000f)) {
                val page = canvas.panBy(dx)
                assertEquals(canvas.width / 2f, page.center.x, 0.5f, "zoom $scale, pan $dx")
            }
        }
    }

    @Test
    fun aPageWiderThanTheWindowStopsAtItsEdges() {
        val canvas = wideWindow()
        for (scale in listOf(2f, 3f)) {
            canvas.zoomTo(scale)
            // Its margin grows with the zoom
            assertEquals(canvas.margin * scale, canvas.panBy(100_000f).left, 0.5f, "zoom $scale, left edge")
            assertEquals(canvas.width - canvas.margin * scale, canvas.panBy(-100_000f).right, 0.5f, "zoom $scale, right edge")
        }
    }

    @Test
    fun theBoundsDoNotJumpWhenTheZoomCrossesTheWidthOfTheWindow() {
        val canvas = wideWindow()
        // The pages with their margin (896 dp) fill the 1440 dp at about 1.607
        var previous: Float? = null
        var scale = 1.55f
        while (scale < 1.67f) {
            canvas.zoomTo(scale)
            val left = canvas.panBy(100_000f).left
            if (previous != null) assertTrue(abs(left - previous) < 2f, "zoom $scale: $previous to $left")
            previous = left
            scale += 0.001f
        }
    }

    @Test
    fun thePagesOfAPhoneDoNotWobbleAtTheZoomOfTheOpening() {
        // 390 dp at 3×, and an Android phone of 1080 pixels at 2.625×
        for ((width, density) in listOf(1170f to 3f, 1080f to 2.625f)) {
            val canvas = Canvas(width = width, density = density)
            val opening = canvas.pageOnScreen()
            assertEquals(canvas.margin, opening.left, 1f, "$width px at $density")
            assertEquals(opening.left, canvas.panBy(100_000f).left, 0.5f, "$width px at $density, pan right")
            assertEquals(opening.left, canvas.panBy(-100_000f).left, 0.5f, "$width px at $density, pan left")
        }
    }

    @Test
    fun aDocumentMixingWidthsIsBoundedByItsWidestPage() {
        val portrait = Page(widthMm = 210f, heightMm = 297f)
        val landscape = Page(widthMm = 297f, heightMm = 210f)
        val canvas = wideWindow(listOf(portrait, landscape))

        canvas.zoomTo(1.25f)
        canvas.panBy(100_000f)
        assertEquals(canvas.width / 2f, canvas.pageOnScreen(0).center.x, 0.5f)
        assertEquals(canvas.width / 2f, canvas.pageOnScreen(1).center.x, 0.5f)

        canvas.zoomTo(2f)
        assertEquals(canvas.margin * 2f, canvas.panBy(100_000f, index = 1).left, 0.5f)
        assertEquals(canvas.width - canvas.margin * 2f, canvas.panBy(-100_000f, index = 1).right, 0.5f)
    }

    @Test
    fun aPagePulledOffItsCenterSpringsBack() {
        val canvas = wideWindow()
        canvas.zoomTo(1.25f)
        val camera = canvas.camera

        // A pinch around a point on the left, then a pull to the right: beyond the bounds
        camera.onDragStart()
        camera.onDrag(0f, 0f, 1.1f, 200f, canvas.height / 2f)
        camera.onDrag(300f, 0f)
        assertTrue(abs(canvas.pageOnScreen().center.x - canvas.width / 2f) > 100f)
        camera.onRelease(0f, 0f)
        canvas.settle()

        assertEquals(canvas.width / 2f, canvas.pageOnScreen().center.x, 0.5f)
    }

    @Test
    fun visibleHorizontalBoundsTrackPortraitAndLandscapePagesSeparately() {
        val portrait = Page(widthMm = 210f, heightMm = 297f)
        val landscape = Page(widthMm = 297f, heightMm = 210f)
        val layout = CalcPage(density = 2f).apply {
            calcPagesRectOnWindow(listOf(portrait, landscape), Rect(0f, 0f, 2880f, 1800f))
        }
        val portraitRect = layout.pagesRectOnWindow[0]
        val landscapeRect = layout.pagesRectOnWindow[1]

        val topOnly = layout.visibleHorizontalBounds(portraitRect.top, portraitRect.bottom)
        assertEquals((portraitRect.left - layout.horizontalPaddingPx) to (portraitRect.right + layout.horizontalPaddingPx), topOnly)

        val bottomOnly = layout.visibleHorizontalBounds(landscapeRect.top, landscapeRect.bottom)
        assertEquals((landscapeRect.left - layout.horizontalPaddingPx) to (landscapeRect.right + layout.horizontalPaddingPx), bottomOnly)

        val camera = CameraPhysicsEngine(density = 2f, getContentRect = { layout.contentRect }).apply {
            visibleHorizontalBounds = { wt, wb -> layout.visibleHorizontalBounds(wt, wb) }
            setViewport(2880f, 900f)
            centerOnWorldPoint(portraitRect.center.x, portraitRect.center.y, 2f, 2880f, 900f)
            restoreToBounds(animated = false)
        }
        // Portrait at 2x fits within 2880 px viewport, so camera centers horizontally instead of panning to landscape edges
        camera.onDrag(100_000f, 0f)
        camera.restoreToBounds(animated = false)
        assertFalse(camera.canMoveX)
    }

    @Test
    fun verticalCameraMarginsScaleWithScreenDensityInDp() {
        for (density in listOf(1f, 2f, 3f)) {
            val canvas = Canvas(width = 1200f, density = density)
            assertEquals(20f * density, canvas.camera.topPaddingPx, 0.01f)
            assertEquals(20f * density, canvas.camera.bottomPaddingPx, 0.01f)
        }
    }

    @Test
    fun aPanMeasuredOnTheScreenLandsThereBeyondTheBounds() {
        val canvas = Canvas(width = 1200f, density = 1f, height = 836f)
        fun drawnTop() = canvas.camera.getRenderMatrix(Matrix2D()).mapPoint(0f, 0f).y
        val before = drawnTop()
        // Past the bottom of the page by half the canvas, where a drag resists
        val pan = -(canvas.layout.contentRect.height - canvas.height / 2f)
        canvas.camera.panDrawnBy(0f, pan)
        assertEquals(before + pan, drawnTop(), 1f)
        canvas.camera.panDrawnBy(0f, -pan)
        assertEquals(before, drawnTop(), 1f)

        canvas.camera.panDrawnBy(0f, pan)
        canvas.camera.restoreToBounds(animated = false)
        assertTrue(drawnTop() > before + pan + 100f, "the pan went past the bounds: ${drawnTop()}")
    }
}
