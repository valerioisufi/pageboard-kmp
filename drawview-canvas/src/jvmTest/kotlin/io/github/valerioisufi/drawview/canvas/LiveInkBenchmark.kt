package io.github.valerioisufi.drawview.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.canvas.geometry.mapRect
import io.github.valerioisufi.drawview.canvas.render.tile.TileKey
import io.github.valerioisufi.drawview.canvas.render.tile.TilePyramid
import io.github.valerioisufi.drawview.canvas.render.tile.TileState
import io.github.valerioisufi.drawview.ink.InkStrokeEngine
import io.github.valerioisufi.drawview.ink.InkStrokeModeller
import io.github.valerioisufi.drawview.model.BrushConfig
import io.github.valerioisufi.drawview.model.InputToolType
import io.github.valerioisufi.drawview.model.StrokeMesh
import io.github.valerioisufi.drawview.model.StrokePoint
import io.github.valerioisufi.drawview.model.StrokeTool
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import io.github.valerioisufi.drawview.store.testing.SyntheticDocuments
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.measureTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The work of the stroke in progress (see `docs/ink-latency.md`): an input modeled, the shape taken
 * out of the engine and drawn as the stroke grows (M1), the pen up committing a stroke over a screen
 * of tiles (M2), and the prediction of an event (M7).
 */
class LiveInkBenchmark {

    /**
     * M1: one input per event, as unbuffered stylus events come, over a stroke of 4 s at 240 Hz: the
     * input modeled, the whole mesh or the outline alone taken out, the stroke drawn.
     */
    @Test
    fun anInputOfTheStrokeInProgress() {
        for (tool in listOf(StrokeTool.PRESSURE_PEN, StrokeTool.MARKER, StrokeTool.HIGHLIGHTER)) {
            val brush = BrushConfig(tool = tool, size = if (tool == StrokeTool.HIGHLIGHTER) 4f else 0.5f)
            val engine = InkStrokeEngine()
            val painter = StrokeMeshPainter()
            val canvas = Canvas(ImageBitmap(2000, 1200))
            val toScreen = Matrix2D().postScale(8f, 8f)
            val add = mutableMapOf<Int, MutableList<Duration>>()
            val mesh = mutableMapOf<Int, MutableList<Duration>>()
            val outline = mutableMapOf<Int, MutableList<Duration>>()
            val draw = mutableMapOf<Int, MutableList<Duration>>()
            repeat(6) { round ->
                engine.startStroke(brush)
                for (i in 0 until 960) {
                    val p = handwriting(i)
                    val a = measureTime { engine.addInput(p.x, p.y, p.pressure, p.timestampMs, InputToolType.STYLUS) }
                    var m: StrokeMesh? = null
                    val b = measureTime { m = engine.currentMesh() }
                    val o = measureTime { engine.currentOutline() }
                    val c = measureTime { m?.let { painter.draw(canvas, it, toScreen) } }
                    // The first round warms up
                    if (round > 0 && i + 1 in BUCKETS) {
                        add.getOrPut(i + 1) { mutableListOf() } += a
                        mesh.getOrPut(i + 1) { mutableListOf() } += b
                        outline.getOrPut(i + 1) { mutableListOf() } += o
                        draw.getOrPut(i + 1) { mutableListOf() } += c
                    }
                }
                engine.finishStroke()
            }
            for (bucket in BUCKETS) {
                report(
                    "M1 $tool after $bucket inputs",
                    "modeled ${add.getValue(bucket).median()}, mesh taken out ${mesh.getValue(bucket).median()}, " +
                        "outline alone ${outline.getValue(bucket).median()}, drawn ${draw.getValue(bucket).median()}"
                )
            }
            engine.release()
        }
    }

    /** M7: the prediction of an event: the filter told a sample and asked, Ink modeling the tail. */
    @Test
    fun thePredictionOfAnEvent() {
        val brush = BrushConfig(tool = StrokeTool.PRESSURE_PEN, size = 0.5f)
        val engine = InkStrokeEngine()
        val predictor = KalmanInkPredictor { 2f }
        val filter = mutableListOf<Duration>()
        val modeling = mutableListOf<Duration>()
        repeat(6) { round ->
            engine.startStroke(brush)
            predictor.start()
            for (i in 0 until 960) {
                val p = handwriting(i)
                engine.addInput(p.x, p.y, p.pressure, p.timestampMs, InputToolType.STYLUS)
                var predicted: List<PredictedPoint> = emptyList()
                val a = measureTime {
                    predictor.record(Offset(p.x * 16f, p.y * 16f), p.timestampMs)
                    predicted = predictor.predict(16L)
                }
                val points = predicted.map { StrokePoint(it.position.x / 16f, it.position.y / 16f, p.pressure, it.timeMs) }
                val b = measureTime { engine.predict(points) }
                if (round > 0) {
                    filter += a
                    modeling += b
                }
            }
            engine.finishStroke()
        }
        engine.release()
        report("M7 the prediction of an event", "filter ${filter.median()}, Ink modeling the tail ${modeling.median()}")
    }

    /** M2: the pen up of a word (60 inputs) and of a long stroke (480 inputs) over a screen of tiles. */
    @Test
    fun thePenUp() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val document = SyntheticDocuments.notebook(pages = 10, strokesPerPage = 400, modeller = InkStrokeModeller)
        val controller = DrawViewController(document = document, coroutineScope = scope, density = 2f)
        try {
            controller.viewport.setSize(VIEWPORT.width, VIEWPORT.height)
            fillTheScreen(controller)
            val brush = BrushConfig(tool = StrokeTool.PRESSURE_PEN, size = 0.5f)
            for (inputs in listOf(60, 480)) {
                val times = List(40) { s ->
                    val points = List(inputs) { i -> handwriting(i).let { StrokePoint(it.x + s % 8 * 20f, it.y + s / 8 * 15f, it.pressure, it.timestampMs) } }
                    val mesh = assertNotNull(InkStrokeModeller.buildMesh(points, brush, InputToolType.STYLUS))
                    measureTime { controller.commitInkStroke(mesh, 0, brush) }
                }.drop(5)
                report("M2 the pen up of a stroke of $inputs inputs", "${times.median()}")
            }
        } finally {
            controller.tileManager.cleanup()
            scope.cancel()
        }
    }

    /** Draws frames until every tile on screen is rasterized. */
    private fun fillTheScreen(controller: DrawViewController) {
        val canvas = Canvas(ImageBitmap(64, 64))
        val start = TimeSource.Monotonic.markNow()
        while (start.elapsedNow() < TIMEOUT) {
            val camera = controller.viewport.getRenderMatrix(Matrix2D())
            val rects = controller.viewport.layout.pagesRectOnWindow
            controller.tileManager.drawTiles(canvas, camera, VIEWPORT, controller.document, rects)
            if (visibleTiles(controller, rects, camera).all { controller.tileManager.tileCache.get(it)?.state == TileState.READY }) return
            Thread.sleep(16L)
        }
        error("The screen is not filled after $TIMEOUT")
    }

    private fun visibleTiles(controller: DrawViewController, rects: List<Rect>, camera: Matrix2D): List<TileKey> {
        val screenToWorld = Matrix2D()
        check(camera.invert(screenToWorld))
        val world = screenToWorld.mapRect(VIEWPORT)
        val lod = TilePyramid.calculateLod(camera.a)
        return rects.indices.filter { rects[it].overlaps(world) }.flatMap { index ->
            val page = rects[index]
            val keys = mutableListOf<TileKey>()
            TilePyramid.getPageIntersectingTileRange(
                max(0f, world.left - page.left), max(0f, world.top - page.top),
                min(page.width, world.right - page.left), min(page.height, world.bottom - page.top),
                page.width, page.height, lod
            ).forEach { col, row -> keys += TileKey(controller.document.pages[index].renderKey, lod, col, row) }
            keys
        }
    }

    /** The input [i] of a line of handwriting at 240 Hz, in page millimeters. */
    private fun handwriting(i: Int): StrokePoint = StrokePoint(
        x = 10f + i * 0.12f + 1.5f * cos(i * 0.4f),
        y = 30f + 3f * sin(i * 0.4f),
        pressure = 0.5f + 0.3f * sin(i * 0.05f),
        timestampMs = i * 4L + 1L
    )

    private fun List<Duration>.median() = sorted()[size / 2]

    private fun report(measure: String, value: String) = println("BENCHMARK $measure: $value")

    private companion object {
        val BUCKETS = listOf(60, 240, 480, 960)
        val VIEWPORT = Rect(0f, 0f, 1600f, 1000f)
        val TIMEOUT: Duration = 60.seconds
    }
}
