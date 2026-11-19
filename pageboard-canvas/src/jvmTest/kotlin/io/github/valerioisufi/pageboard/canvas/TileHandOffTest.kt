package io.github.valerioisufi.pageboard.canvas

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import io.github.valerioisufi.pageboard.canvas.controller.PageboardController
import io.github.valerioisufi.pageboard.model.Document
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * What the hand-off of a stroke to the tiles asks of them (see `docs/plans/ink-latency.md`): a cell with
 * no tile yet is told apart, because the buffer over it must stay until the tile comes.
 */
class TileHandOffTest {

    @Test
    fun aCellWaitingForItsFirstTileIsNotDrawn() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = PageboardController(document = Document(), coroutineScope = scope)
        val scene = ImageComposeScene(width = 800, height = 1200, density = Density(1f)) { PageCanvas(controller) }
        val center = Rect(300f, 500f, 500f, 700f)
        try {
            scene.render()
            assertFalse(controller.tileManager.isScreenRegionDrawn(center), "no tile rasterized at the first frame")
            val deadline = System.currentTimeMillis() + 10_000L
            while (!controller.tileManager.isScreenRegionDrawn(center) && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L)
                scene.render()
            }
            assertTrue(controller.tileManager.isScreenRegionDrawn(center), "the tiles came")
        } finally {
            scene.close()
            scope.cancel()
        }
    }
}
