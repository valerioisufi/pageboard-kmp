package io.github.valerioisufi.drawview.ink

import io.github.valerioisufi.drawview.model.BrushConfig
import io.github.valerioisufi.drawview.model.InputToolType
import io.github.valerioisufi.drawview.model.StrokeEngineDelegate
import io.github.valerioisufi.drawview.model.StrokeMesh
import io.github.valerioisufi.drawview.model.StrokeOutline
import io.github.valerioisufi.drawview.model.StrokePoint

/**
 * Low level binding of the C API declared in `native/bridge/ink_bridge.h`. Handles are opaque
 * native pointers.
 */
interface InkNativeApi {
    fun createBrush(familyType: Int, size: Float, r: Float, g: Float, b: Float, a: Float, epsilon: Float): Long
    fun destroyBrush(brushHandle: Long)
    fun createStrokeBuilder(): Long
    fun startStroke(builderHandle: Long, brushHandle: Long, strokeUnitLengthCm: Float)
    fun addPoint(builderHandle: Long, x: Float, y: Float, pressure: Float, timeMs: Long, toolType: Int)
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
class NativeStrokeEngineDelegate(private val api: InkNativeApi) : StrokeEngineDelegate {
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

    override fun finishInputs() {
        if (!started) return
        api.finishInputs(builderHandle)
    }

    override fun currentMesh(brush: BrushConfig, points: List<StrokePoint>, toolType: InputToolType): StrokeMesh? {
        if (!started) return null
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
            inputToolType = toolType
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
