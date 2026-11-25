package io.github.valerioisufi.pageboard.ink

import io.github.valerioisufi.pageboard.model.BrushConfig
import io.github.valerioisufi.pageboard.model.InputToolType
import io.github.valerioisufi.pageboard.model.StrokeEngineDelegate
import io.github.valerioisufi.pageboard.model.StrokeMesh
import io.github.valerioisufi.pageboard.model.StrokeOutline
import io.github.valerioisufi.pageboard.model.StrokePoint
import io.github.valerioisufi.pageboard.model.geometry.Rect

/**
 * Low level binding of the C API declared in `native/bridge/ink_bridge.h`. Handles are opaque
 * native pointers.
 */
internal interface InkNativeApi {
    fun createBrush(familyType: Int, size: Float, r: Float, g: Float, b: Float, a: Float, epsilon: Float): Long
    fun destroyBrush(brushHandle: Long)
    fun createStrokeBuilder(): Long
    fun startStroke(builderHandle: Long, brushHandle: Long, strokeUnitLengthCm: Float)
    fun addPoint(builderHandle: Long, x: Float, y: Float, pressure: Float, timeMs: Long, toolType: Int)
    fun addPoints(builderHandle: Long, xs: FloatArray, ys: FloatArray, pressures: FloatArray, timesMs: LongArray, toolType: Int)
    fun predict(builderHandle: Long, xs: FloatArray, ys: FloatArray, pressures: FloatArray, timesMs: LongArray)
    fun takeUpdatedRegion(builderHandle: Long, outBounds: FloatArray): Boolean
    fun finishInputs(builderHandle: Long)
    fun clearStroke(builderHandle: Long)
    fun destroyStrokeBuilder(builderHandle: Long)
    fun getVertexCount(builderHandle: Long): Int
    fun getTriangleCount(builderHandle: Long): Int
    fun getMeshData(builderHandle: Long, outPositions: FloatArray, outIndices: IntArray): Boolean
    fun getOutlineCount(builderHandle: Long): Int
    fun getOutlineVertexCount(builderHandle: Long): Int
    fun getOutlines(builderHandle: Long, outPositions: FloatArray, outLengths: IntArray): Boolean
}

/**
 * [StrokeEngineDelegate] backed by the Google Ink C++ engine.
 */
internal class NativeStrokeEngineDelegate(private val api: InkNativeApi) : StrokeEngineDelegate {
    private var builderHandle: Long = 0L
    private var brushHandle: Long = 0L
    private var brushKey: BrushConfig? = null
    private var started = false

    override val isNative: Boolean get() = true

    override fun start(brush: BrushConfig, strokeUnitLengthCm: Float) {
        if (builderHandle == 0L) {
            builderHandle = api.createStrokeBuilder()
        }
        if (brushHandle == 0L || brushKey != brush) {
            if (brushHandle != 0L) api.destroyBrush(brushHandle)
            brushHandle = api.createBrush(
                brush.tool.brushFamily.nativeId,
                brush.size,
                ((brush.color ushr 16) and 0xFF) / 255f,
                ((brush.color ushr 8) and 0xFF) / 255f,
                (brush.color and 0xFF) / 255f,
                ((brush.color ushr 24) and 0xFF) / 255f,
                brush.epsilon
            )
            brushKey = brush
        }
        if (builderHandle == 0L || brushHandle == 0L) {
            started = false
            return
        }
        api.startStroke(builderHandle, brushHandle, strokeUnitLengthCm)
        started = true
    }

    override fun addInput(x: Float, y: Float, pressure: Float, timeMs: Long, toolType: InputToolType) {
        if (!started) return
        val p = if (toolType == InputToolType.STYLUS) pressure else -1f
        api.addPoint(builderHandle, x, y, p, timeMs, toolType.nativeId)
    }

    /** Adds [points] from the index [from] at once, as [addInput] would add them one by one. */
    fun addInputs(points: List<StrokePoint>, from: Int, toolType: InputToolType) {
        if (!started) return
        val count = points.size - from
        if (count <= 0) return
        val xs = FloatArray(count)
        val ys = FloatArray(count)
        val pressures = FloatArray(count)
        val times = LongArray(count)
        val stylus = toolType == InputToolType.STYLUS
        for (i in 0 until count) {
            val p = points[from + i]
            xs[i] = p.x
            ys[i] = p.y
            pressures[i] = if (stylus) p.pressure else -1f
            times[i] = p.timestampMs
        }
        api.addPoints(builderHandle, xs, ys, pressures, times, toolType.nativeId)
    }

    /** Predicts the stroke beyond its last input with [points] (none: no prediction), see `ink_stroke_builder_predict`. */
    fun predict(points: List<StrokePoint>, toolType: InputToolType) {
        if (!started) return
        val count = points.size
        val xs = FloatArray(count)
        val ys = FloatArray(count)
        val pressures = FloatArray(count)
        val times = LongArray(count)
        val stylus = toolType == InputToolType.STYLUS
        for (i in 0 until count) {
            val p = points[i]
            xs[i] = p.x
            ys[i] = p.y
            pressures[i] = if (stylus) p.pressure else -1f
            times[i] = p.timestampMs
        }
        api.predict(builderHandle, xs, ys, pressures, times)
    }

    /** The rectangle of the geometry changed since the last call, in stroke units; `null` when nothing changed. */
    fun takeUpdatedRegion(): Rect? {
        if (!started) return null
        val bounds = FloatArray(4)
        if (!api.takeUpdatedRegion(builderHandle, bounds)) return null
        return Rect(bounds[0], bounds[1], bounds[2], bounds[3])
    }

    override fun finishInputs() {
        if (!started) return
        api.finishInputs(builderHandle)
    }

    override fun currentMesh(brush: BrushConfig, points: List<StrokePoint>, toolType: InputToolType): StrokeMesh? {
        if (!started) return null
        val outline = currentOutline()?.takeUnless { it.isEmpty }
        if (outline != null) {
            // Outline-only mesh: avoids copying duplicate triangle position and index buffers
            return StrokeMesh(
                positions = FloatArray(0),
                indices = ShortArray(0),
                color = brush.color,
                tool = brush.tool,
                size = brush.size,
                points = points,
                inputToolType = toolType,
                outline = outline
            )
        }

        val vCount = api.getVertexCount(builderHandle)
        val tCount = api.getTriangleCount(builderHandle)
        if (vCount <= 0 || tCount <= 0) return null

        val positions = FloatArray(vCount * 2)
        val indices = IntArray(tCount * 3)
        if (!api.getMeshData(builderHandle, positions, indices)) return null

        return StrokeMesh.fromIndexedMesh(
            positions = positions,
            indices = indices,
            color = brush.color,
            tool = brush.tool,
            size = brush.size,
            points = points,
            inputToolType = toolType,
            outline = null
        )
    }

    override fun currentOutline(): StrokeOutline? {
        if (!started) return null
        val outlineCount = api.getOutlineCount(builderHandle)
        val vertexCount = api.getOutlineVertexCount(builderHandle)
        if (outlineCount <= 0 || vertexCount <= 0) return null

        val positions = FloatArray(vertexCount * 2)
        val lengths = IntArray(outlineCount)
        if (!api.getOutlines(builderHandle, positions, lengths)) return null

        var offset = 0
        val polygons = lengths.map { length ->
            positions.copyOfRange(offset * 2, (offset + length) * 2).also { offset += length }
        }
        return StrokeOutline(polygons)
    }

    override fun clear() {
        if (builderHandle != 0L) api.clearStroke(builderHandle)
        started = false
    }

    override fun release() {
        if (builderHandle != 0L) {
            api.destroyStrokeBuilder(builderHandle)
            builderHandle = 0L
        }
        if (brushHandle != 0L) {
            api.destroyBrush(brushHandle)
            brushHandle = 0L
        }
        brushKey = null
        started = false
    }
}
