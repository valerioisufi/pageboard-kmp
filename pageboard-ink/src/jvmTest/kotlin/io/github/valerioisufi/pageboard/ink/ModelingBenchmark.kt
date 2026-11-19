package io.github.valerioisufi.pageboard.ink

import io.github.valerioisufi.pageboard.model.BrushConfig
import io.github.valerioisufi.pageboard.model.InputToolType
import io.github.valerioisufi.pageboard.model.StrokePoint
import io.github.valerioisufi.pageboard.model.StrokeTool
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.time.measureTime

/**
 * The cost of modeling the strokes of a document as it is loaded (see `docs/plans/performance.md`): the
 * inputs of a stroke given at once, as `InkStrokeModeller` gives them, against the inputs added one
 * by one to one engine for all, as the editor adds them.
 */
class ModelingBenchmark {
    private val brush = BrushConfig(tool = StrokeTool.PRESSURE_PEN, size = 0.5f)
    private val strokes = List(STROKES) { s ->
        List(120) { i -> StrokePoint(20f + i * 0.15f, 30f + s % 50 * 5f + 2.5f * sin(i * 0.3f), 0.4f + 0.3f * sin(i * 0.1f), i * 8L) }
    }

    @Test
    fun modelingStrokes() {
        // Warm up
        strokes.take(200).forEach { assertNotNull(InkStrokeModeller.buildMesh(it, brush, InputToolType.STYLUS)) }

        val whole = measureTime {
            for (points in strokes) assertNotNull(InkStrokeModeller.buildMesh(points, brush, InputToolType.STYLUS))
        }
        report("M5 a stroke modeled from its inputs at once", "${whole / STROKES}")

        val engine = InkStrokeEngine()
        val oneByOne = measureTime {
            for (points in strokes) {
                engine.startStroke(brush)
                for (p in points) engine.addInput(p.x, p.y, p.pressure, p.timestampMs, InputToolType.STYLUS)
                assertNotNull(engine.finishStroke())
            }
        }
        engine.release()
        report("M5 a stroke modeled input by input", "${oneByOne / STROKES}")
    }

    private fun report(measure: String, value: String) = println("BENCHMARK $measure: $value")

    private companion object {
        const val STROKES = 2_000
    }
}
