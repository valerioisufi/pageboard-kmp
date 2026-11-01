package io.github.valerioisufi.drawview.canvas

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.model.DrawDocument
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * What the hand-off of a stroke to the tiles asks of them (see `docs/ink-latency.md`): a cell with
 * no tile yet is told apart, because the buffer over it must stay until the tile comes.
 */
class TileHandOffTest {

    @Test
    fun aCellWaitingForItsFirstTileIsNotDrawn() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = DrawViewController(document = DrawDocument(), coroutineScope = scope)
        val scene = ImageComposeScene(width = 800, height = 1200, density = Density(1f)) { DrawCanvas(controller) }
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
