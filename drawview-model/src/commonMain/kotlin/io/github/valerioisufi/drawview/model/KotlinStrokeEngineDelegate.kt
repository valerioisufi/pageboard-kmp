package io.github.valerioisufi.drawview.model

import io.github.valerioisufi.drawview.model.BrushConfig
import io.github.valerioisufi.drawview.model.InputToolType
import io.github.valerioisufi.drawview.model.StrokeMesh
import io.github.valerioisufi.drawview.model.StrokePoint
import io.github.valerioisufi.drawview.model.StrokeTool
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Pure Kotlin stroke tessellator.
 *
 * Used where the Google Ink engine is not available (web targets) and as a graceful fallback
 * when the native library cannot be loaded. It builds a single, non self-overlapping triangle
 * strip along the smoothed input polyline (so translucent highlighter strokes blend uniformly)
 * closed by round caps. Dashed brushes (lasso) produce one strip per dash.
 */
public class KotlinStrokeEngineDelegate : StrokeEngineDelegate {
    private val xs = ArrayList<Float>()
    private val ys = ArrayList<Float>()
    private val radii = ArrayList<Float>()
    private var brush: BrushConfig = BrushConfig()
    private var started = false

    // Last raw input: the smoothed polyline lags behind it, the stroke must end exactly there
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var lastRawRadius = 0f
    private var hasRawInput = false

    override val isNative: Boolean get() = false

    override fun start(brush: BrushConfig, strokeUnitLengthCm: Float) {
        this.brush = brush
        xs.clear(); ys.clear(); radii.clear()
        hasRawInput = false
        started = true
    }

    override fun addInput(x: Float, y: Float, pressure: Float, timeMs: Long, toolType: InputToolType) {
        if (!started) return
        val baseRadius = brush.size / 2f
        val radius = if (brush.tool == StrokeTool.PRESSURE_PEN && toolType == InputToolType.STYLUS && pressure >= 0f) {
            // Mirrors the stock pressure pen: up to 1.5x the size at full pressure
            baseRadius * (0.75f + 0.75f * pressure.coerceIn(0f, 1f))
        } else {
            baseRadius
        }
        lastRawX = x
        lastRawY = y
        lastRawRadius = radius
        hasRawInput = true

        if (xs.isNotEmpty()) {
            val lx = xs[xs.size - 1]
            val ly = ys[ys.size - 1]
            if (hypot(x - lx, y - ly) < minDistance()) return
        }
        // Exponential smoothing of the positions removes digitizer jitter
        if (xs.size >= 2) {
            val sx = xs[xs.size - 1] * 0.35f + x * 0.65f
            val sy = ys[ys.size - 1] * 0.35f + y * 0.65f
            xs.add(sx); ys.add(sy)
        } else {
            xs.add(x); ys.add(y)
        }
        radii.add(radius)
    }

    /**
     * Ends the polyline on the last raw input (the exponential smoothing lags behind it), as the
     * Ink stroke modeler does when the inputs are finished.
     */
    override fun finishInputs() {
        if (!started || !hasRawInput || xs.isEmpty()) return
        val last = xs.size - 1
        val distance = hypot(lastRawX - xs[last], lastRawY - ys[last])
        if (distance <= 1e-4f) return
        if (distance < minDistance() * 0.5f && xs.size >= 2) {
            // Too close to the last sample: move it instead of adding a degenerate segment
            xs[last] = lastRawX
            ys[last] = lastRawY
            radii[last] = lastRawRadius
        } else {
            xs.add(lastRawX)
            ys.add(lastRawY)
            radii.add(lastRawRadius)
        }
    }

    private fun minDistance(): Float = max(brush.epsilon, brush.size * 0.05f)

    override fun currentMesh(brush: BrushConfig, points: List<StrokePoint>, toolType: InputToolType): StrokeMesh? {
        if (!started || xs.isEmpty()) return null
        val positions = ArrayList<Float>()
        val indices = ArrayList<Int>()

        if (brush.tool.brushFamily == StrokeTool.DASHED_LINE) {
            buildDashes(positions, indices)
        } else {
            buildStrip(0, xs.size - 1, positions, indices)
        }
        if (indices.isEmpty()) return null

        return StrokeMesh.fromIndexedMesh(
            positions = positions.toFloatArray(),
            indices = indices.toIntArray(),
            color = brush.color,
            tool = brush.tool,
            size = brush.size,
            points = points,
            inputToolType = toolType
        )
    }

    override fun clear() {
        xs.clear(); ys.clear(); radii.clear()
        hasRawInput = false
        started = false
    }

    override fun release(): Unit = clear()

    private fun buildDashes(positions: MutableList<Float>, indices: MutableList<Int>) {
        val dash = brush.size * 4f
        val gap = brush.size * 3f
        var travelled = 0f
        var dashStart = 0
        var drawing = true
        for (i in 1 until xs.size) {
            travelled += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
            val limit = if (drawing) dash else gap
            if (travelled >= limit) {
                if (drawing) buildStrip(dashStart, i, positions, indices)
                drawing = !drawing
                dashStart = i
                travelled = 0f
            }
        }
        if (drawing) buildStrip(dashStart, xs.size - 1, positions, indices)
    }

    /**
     * Appends a strip covering the samples [from]..[to] (inclusive) with round caps.
     */
    private fun buildStrip(from: Int, to: Int, positions: MutableList<Float>, indices: MutableList<Int>) {
        if (to < from) return
        if (to == from) {
            appendDisc(xs[from], ys[from], radii[from], positions, indices)
            return
        }

        val base = positions.size / 2
        for (i in from..to) {
            // Averaged normal at the joint keeps the strip continuous without overlaps
            val prevX = xs[max(from, i - 1)]; val prevY = ys[max(from, i - 1)]
            val nextX = xs[min(to, i + 1)]; val nextY = ys[min(to, i + 1)]
            var dx = nextX - prevX
            var dy = nextY - prevY
            val len = hypot(dx, dy)
            if (len > 0f) { dx /= len; dy /= len } else { dx = 1f; dy = 0f }
            val nx = -dy * radii[i]
            val ny = dx * radii[i]
            positions.add(xs[i] + nx); positions.add(ys[i] + ny)
            positions.add(xs[i] - nx); positions.add(ys[i] - ny)
        }
        val count = to - from + 1
        for (i in 0 until count - 1) {
            val l0 = base + i * 2
            val r0 = l0 + 1
            val l1 = l0 + 2
            val r1 = l0 + 3
            indices.add(l0); indices.add(r0); indices.add(l1)
            indices.add(r0); indices.add(r1); indices.add(l1)
        }

        appendCap(from, from + 1, base, base + 1, positions, indices)
        val lastLeft = base + (count - 1) * 2
        appendCap(to, to - 1, lastLeft + 1, lastLeft, positions, indices)
    }

    /**
     * Half-disc fan closing the strip at sample [at], pointing away from sample [towards].
     * The fan sweeps from [leftVertex] (at `outward - 90°`) to [rightVertex] (at `outward + 90°`).
     */
    private fun appendCap(
        at: Int,
        towards: Int,
        leftVertex: Int,
        rightVertex: Int,
        positions: MutableList<Float>,
        indices: MutableList<Int>
    ) {
        val cx = xs[at]; val cy = ys[at]; val r = radii[at]
        val dirAngle = atan2(cy - ys[towards], cx - xs[towards])
        val segments = segmentsFor(r) / 2
        val center = positions.size / 2
        positions.add(cx); positions.add(cy)
        var previous = leftVertex
        for (s in 1 until segments) {
            val angle = dirAngle - PI.toFloat() / 2f + PI.toFloat() * s / segments
            positions.add(cx + cos(angle) * r); positions.add(cy + sin(angle) * r)
            val current = positions.size / 2 - 1
            indices.add(center); indices.add(previous); indices.add(current)
            previous = current
        }
        indices.add(center); indices.add(previous); indices.add(rightVertex)
    }

    private fun appendDisc(cx: Float, cy: Float, r: Float, positions: MutableList<Float>, indices: MutableList<Int>) {
        val segments = segmentsFor(r)
        val center = positions.size / 2
        positions.add(cx); positions.add(cy)
        val first = center + 1
        for (s in 0 until segments) {
            val angle = 2f * PI.toFloat() * s / segments
            positions.add(cx + cos(angle) * r); positions.add(cy + sin(angle) * r)
        }
        for (s in 0 until segments) {
            indices.add(center)
            indices.add(first + s)
            indices.add(first + (s + 1) % segments)
        }
    }

    private fun segmentsFor(radius: Float): Int {
        val tolerance = max(brush.epsilon, 0.01f)
        // Chord error of a regular polygon: r * (1 - cos(pi / n)) <= tolerance
        val n = if (radius <= tolerance) 8 else ceil(PI / kotlin.math.acos((1f - tolerance / radius).coerceIn(-1f, 1f))).toInt()
        return n.coerceIn(8, 48).let { if (it % 2 == 0) it else it + 1 }
    }
}
