package io.github.valerioisufi.drawview.ink

import io.github.valerioisufi.drawview.model.BrushConfig
import io.github.valerioisufi.drawview.model.InputToolType
import io.github.valerioisufi.drawview.model.STROKE_UNIT_LENGTH_CM_MM
import io.github.valerioisufi.drawview.model.StrokeEngineDelegate
import io.github.valerioisufi.drawview.model.StrokeMesh
import io.github.valerioisufi.drawview.model.StrokeOutline
import io.github.valerioisufi.drawview.model.StrokePoint
import io.github.valerioisufi.drawview.model.geometry.Rect

/**
 * Real-time stroke modeller.
 *
 * Inputs are fed in stroke units (page millimeters) and produce a triangle mesh with the outline
 * of the stroke ([StrokeMesh.outline]), filled to draw it. The heavy lifting is done by the Google
 * Ink C++ engine through the platform bridge (JNI on Android and desktop, cinterop on iOS); when
 * the native engine is not available (a native library that failed to load) a pure Kotlin
 * tessellator is used instead, which gives the triangles only.
 */
public class InkStrokeEngine {
    private val delegate: StrokeEngineDelegate = createStrokeEngineDelegate()

    private val recordedPoints = mutableListOf<StrokePoint>()
    private var currentBrush: BrushConfig = BrushConfig()
    private var currentToolType: InputToolType = InputToolType.UNKNOWN
    private var syntheticClockMs: Long = 0L
    private var isActive = false

    /** Whether strokes are modeled by the native Google Ink engine. */
    public val isNative: Boolean get() = delegate.isNative

    /**
     * Starts a new stroke, discarding any stroke in progress.
     *
     * @param strokeUnitLengthCm physical length of one stroke unit in centimeters (used by the
     * brush behaviors based on physical speed/acceleration), or a value <= 0 if unknown.
     */
    public fun startStroke(brush: BrushConfig, strokeUnitLengthCm: Float = STROKE_UNIT_LENGTH_CM_MM) {
        currentBrush = brush
        currentToolType = InputToolType.UNKNOWN
        recordedPoints.clear()
        syntheticClockMs = 0L
        isActive = true
        delegate.start(brush, strokeUnitLengthCm)
    }

    /**
     * Adds an input to the current stroke without extracting the mesh. Use [currentMesh] once
     * per frame after feeding all the (historical) samples of an event.
     */
    public fun addInput(
        x: Float,
        y: Float,
        pressure: Float = -1f,
        timeMs: Long = 0L,
        toolType: InputToolType = InputToolType.UNKNOWN
    ) {
        if (!isActive) return
        if (recordedPoints.isEmpty()) currentToolType = toolType
        val t = if (timeMs > 0L) timeMs else {
            // Callers without timestamps: synthesize a 60 Hz clock
            syntheticClockMs += 16L
            syntheticClockMs
        }
        recordedPoints.add(StrokePoint(x, y, pressure, t))
        delegate.addInput(x, y, pressure, t, currentToolType)
    }

    /**
     * Adds the inputs of a stroke at once, modeled as [addInput] models them one by one: the
     * native engine takes them in one call and updates the shape once.
     */
    internal fun addInputs(points: List<StrokePoint>, toolType: InputToolType) {
        if (!isActive || points.isEmpty()) return
        if (recordedPoints.isEmpty()) currentToolType = toolType
        val from = recordedPoints.size
        for (p in points) {
            val t = if (p.timestampMs > 0L) p.timestampMs else {
                syntheticClockMs += 16L
                syntheticClockMs
            }
            recordedPoints.add(StrokePoint(p.x, p.y, p.pressure, t))
        }
        val native = delegate as? NativeStrokeEngineDelegate
        if (native != null) {
            native.addInputs(recordedPoints, from, currentToolType)
        } else {
            for (i in from until recordedPoints.size) {
                val p = recordedPoints[i]
                delegate.addInput(p.x, p.y, p.pressure, p.timestampMs, currentToolType)
            }
        }
    }

    /**
     * Predicts the stroke in progress beyond its last input with [points] (page millimeters, times
     * after the last input, oldest first): Ink models them with the brush of the stroke, and the next
     * inputs or the next prediction replace them. They never become [inputs], and the mesh of
     * [finishStroke] has none of them. An empty list takes the prediction away. Without the native
     * engine nothing is predicted.
     */
    public fun predict(points: List<StrokePoint>) {
        if (!isActive || recordedPoints.isEmpty()) return
        (delegate as? NativeStrokeEngineDelegate)?.predict(points, currentToolType)
    }

    /**
     * The rectangle of the stroke in progress that changed since the last call (or its start), in
     * stroke units, the prediction taken away too: what a layer that keeps the stroke drawn must draw
     * again. `null` when nothing changed, or without the native engine.
     */
    public fun takeUpdatedRegion(): Rect? {
        if (!isActive) return null
        return (delegate as? NativeStrokeEngineDelegate)?.takeUpdatedRegion()
    }

    /**
     * Adds an input and returns the updated mesh of the stroke in progress.
     */
    public fun addPoint(
        x: Float,
        y: Float,
        pressure: Float = -1f,
        timeMs: Long = 0L,
        toolType: InputToolType = InputToolType.UNKNOWN
    ): StrokeMesh? {
        addInput(x, y, pressure, timeMs, toolType)
        return currentMesh()
    }

    /**
     * Mesh of the stroke in progress (without the recorded inputs, which are attached only to
     * the final mesh returned by [finishStroke]).
     */
    public fun currentMesh(): StrokeMesh? {
        if (!isActive) return null
        return delegate.currentMesh(currentBrush, emptyList(), currentToolType)
    }

    /**
     * Outline of the stroke in progress alone, without its triangles: what the stroke drawn live
     * needs, taken out of the engine at less cost than [currentMesh]. `null` when there is none, or
     * when the modeller gives no outline (the Kotlin tessellator): [currentMesh] draws it then.
     */
    public fun currentOutline(): StrokeOutline? {
        if (!isActive) return null
        return delegate.currentOutline()?.takeUnless { it.isEmpty }
    }

    /**
     * Completes the stroke and returns its final mesh (with the recorded inputs), or `null` if
     * no geometry was produced.
     */
    public fun finishStroke(): StrokeMesh? {
        if (!isActive) return null
        isActive = false
        delegate.finishInputs()
        val mesh = delegate.currentMesh(currentBrush, recordedPoints.toList(), currentToolType)
        delegate.clear()
        recordedPoints.clear()
        return mesh
    }

    public fun cancelStroke() {
        isActive = false
        delegate.clear()
        recordedPoints.clear()
    }

    public fun release() {
        isActive = false
        recordedPoints.clear()
        delegate.release()
    }

    /** Recorded inputs of the stroke in progress. */
    public val inputs: List<StrokePoint> get() = recordedPoints

    public companion object {
        /**
         * Models a complete stroke from recorded inputs (e.g. when loading a document or after a
         * geometric transformation).
         */
        public fun buildMesh(
            points: List<StrokePoint>,
            brush: BrushConfig,
            toolType: InputToolType = InputToolType.UNKNOWN,
            strokeUnitLengthCm: Float = STROKE_UNIT_LENGTH_CM_MM
        ): StrokeMesh? {
            if (points.isEmpty()) return null
            val engine = InkStrokeEngine()
            return try {
                engine.startStroke(brush, strokeUnitLengthCm)
                engine.addInputs(points, toolType)
                engine.finishStroke()
            } finally {
                engine.release()
            }
        }

    }
}

/**
 * Creates the best stroke modeller available on the current platform.
 */
internal expect fun createStrokeEngineDelegate(): StrokeEngineDelegate
