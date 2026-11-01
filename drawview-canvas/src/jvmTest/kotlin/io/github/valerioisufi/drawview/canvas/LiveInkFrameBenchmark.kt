package io.github.valerioisufi.drawview.canvas

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.unit.Density
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.canvas.tool.Tool
import io.github.valerioisufi.drawview.ink.InkStrokeModeller
import io.github.valerioisufi.drawview.store.testing.SyntheticDocuments
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * M6 (see `docs/ink-latency.md`): a frame of the canvas while a stroke is drawn over a page of 400
 * strokes, as Compose draws it on the desktop (recorded and rasterized on the CPU).
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class LiveInkFrameBenchmark {

    @Test
    fun aFrameWhileDrawing() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val document = SyntheticDocuments.notebook(pages = 3, strokesPerPage = 400, modeller = InkStrokeModeller)
        val controller = DrawViewController(document = document, coroutineScope = scope)
        controller.selectTool(Tool.INK_PEN)
        val scene = ImageComposeScene(width = 1600, height = 1000, density = Density(1f)) { DrawCanvas(controller) }
        try {
            // The tiles of the screen rasterized
            repeat(120) {
                scene.render()
                Thread.sleep(16L)
            }
            val frames = mutableListOf<Duration>()
            repeat(6) { round ->
                var time = 10_000L * (round + 1)
                scene.send(PointerEventType.Press, at(0), time)
                for (i in 1..240) {
                    time += 4L
                    scene.send(PointerEventType.Move, at(i), time)
                    val start = System.nanoTime()
                    scene.render(time * 1_000_000L)
                    if (round > 0) frames += (System.nanoTime() - start).nanoseconds
                }
                scene.send(PointerEventType.Release, at(240), time + 4L)
                scene.render()
            }
            println("BENCHMARK M6 a frame while drawing over a page of 400 strokes: ${frames.sorted()[frames.size / 2]} (p95 ${frames.sorted()[frames.size * 95 / 100]})")
        } finally {
            scene.close()
            scope.cancel()
        }
    }

    /** The point [i] of a line of handwriting across the screen, in pixels. */
    private fun at(i: Int) = Offset(300f + i * 3f + 12f * cos(i * 0.4f), 500f + 24f * sin(i * 0.4f))

    private fun ImageComposeScene.send(type: PointerEventType, position: Offset, timeMillis: Long) {
        sendPointerEvent(
            eventType = type,
            pointers = listOf(ComposeScenePointer(PointerId(0), position, pressed = type != PointerEventType.Release, type = PointerType.Mouse)),
            timeMillis = timeMillis
        )
    }
}
