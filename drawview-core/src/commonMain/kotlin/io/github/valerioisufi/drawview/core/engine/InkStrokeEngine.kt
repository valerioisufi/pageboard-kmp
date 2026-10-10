package io.github.valerioisufi.drawview.core.engine

import io.github.valerioisufi.drawview.core.model.DrawStroke
import io.github.valerioisufi.drawview.model.BrushConfig
import io.github.valerioisufi.drawview.model.InputToolType
import io.github.valerioisufi.drawview.model.StrokeMesh
import io.github.valerioisufi.drawview.model.StrokeOutline
import io.github.valerioisufi.drawview.model.StrokePoint
import io.github.valerioisufi.drawview.model.triangleOutline

/**
 * Physical length of one stroke unit: DrawView models every stroke in page millimeters.
 */
const val STROKE_UNIT_LENGTH_CM_MM: Float = 0.1f

/**
 * Real-time stroke modeller.
 *
 * Inputs are fed in stroke units (page millimeters) and produce a triangle mesh ready to be
 * rendered with `drawVertices`. The heavy lifting is done by the Google Ink C++ engine through
 * the platform bridge (JNI on Android and desktop, cinterop on iOS); when the native engine is
 * not available (web targets, or a native library that failed to load) a pure Kotlin
 * tessellator is used instead.
 */
class InkStrokeEngine {
    private val delegate: StrokeEngineDelegate = createStrokeEngineDelegate()

    private val recordedPoints = mutableListOf<StrokePoint>()
    private var currentBrush: BrushConfig = BrushConfig()
    private var currentToolType: InputToolType = InputToolType.UNKNOWN
    private var syntheticClockMs: Long = 0L
    private var isActive = false

    /** Whether strokes are modeled by the native Google Ink engine. */
    val isNative: Boolean get() = delegate.isNative

    /**
     * Starts a new stroke, discarding any stroke in progress.
     *
     * @param strokeUnitLengthCm physical length of one stroke unit in centimeters (used by the
     * brush behaviors based on physical speed/acceleration), or a value <= 0 if unknown.
     */
    fun startStroke(brush: BrushConfig, strokeUnitLengthCm: Float = STROKE_UNIT_LENGTH_CM_MM) {
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
    fun addInput(
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
     * Adds an input and returns the updated mesh of the stroke in progress.
     */
    fun addPoint(
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
    fun currentMesh(): StrokeMesh? {
        if (!isActive) return null
        return delegate.currentMesh(currentBrush, emptyList(), currentToolType)
    }

    /**
     * Completes the stroke and returns its final mesh (with the recorded inputs), or `null` if
     * no geometry was produced.
     */
    fun finishStroke(): StrokeMesh? {
        if (!isActive) return null
        isActive = false
        delegate.finishInputs()
        val mesh = delegate.currentMesh(currentBrush, recordedPoints.toList(), currentToolType)
        delegate.clear()
        recordedPoints.clear()
        return mesh
    }

    /**
     * Completes the stroke and returns its outline instead of its mesh, or `null` when the
     * modeller cannot give it (see [StrokeEngineDelegate.currentOutline]).
     */
    fun finishStrokeOutline(): StrokeOutline? {
        if (!isActive) return null
        isActive = false
        delegate.finishInputs()
        val outline = delegate.currentOutline()
        delegate.clear()
        recordedPoints.clear()
        return outline?.takeUnless { it.isEmpty }
    }

    fun cancelStroke() {
        isActive = false
        delegate.clear()
        recordedPoints.clear()
    }

    fun release() {
        isActive = false
        recordedPoints.clear()
        delegate.release()
    }

    /** Recorded inputs of the stroke in progress. */
    val inputs: List<StrokePoint> get() = recordedPoints

    companion object {
        /**
         * Models a complete stroke from recorded inputs (e.g. when loading a document or after a
         * geometric transformation).
         */
        fun buildMesh(
            points: List<StrokePoint>,
            brush: BrushConfig,
            toolType: InputToolType = InputToolType.UNKNOWN,
            strokeUnitLengthCm: Float = STROKE_UNIT_LENGTH_CM_MM
        ): StrokeMesh? {
            if (points.isEmpty()) return null
            val engine = InkStrokeEngine()
            return try {
                engine.startStroke(brush, strokeUnitLengthCm)
                for (p in points) {
                    engine.addInput(p.x, p.y, p.pressure, p.timestampMs, toolType)
                }
                engine.finishStroke()
            } finally {
                engine.release()
            }
        }

        /**
         * Outline of a complete stroke modeled from recorded inputs, or `null` when the engine
         * cannot give one (no inputs, or not the native engine).
         */
        fun buildOutline(
            points: List<StrokePoint>,
            brush: BrushConfig,
            toolType: InputToolType = InputToolType.UNKNOWN,
            strokeUnitLengthCm: Float = STROKE_UNIT_LENGTH_CM_MM
        ): StrokeOutline? {
            if (points.isEmpty()) return null
            val engine = InkStrokeEngine()
            return try {
                engine.startStroke(brush, strokeUnitLengthCm)
                for (p in points) {
                    engine.addInput(p.x, p.y, p.pressure, p.timestampMs, toolType)
                }
                engine.finishStrokeOutline()
            } finally {
                engine.release()
            }
        }

        /**
         * Outline of [stroke] on its page: modeled again from its inputs (the outlines of Ink),
         * or the triangles of its mesh when the engine cannot give it.
         */
        fun outline(stroke: DrawStroke): StrokeOutline {
            val base = stroke.baseMesh
            val modeled = buildOutline(base.points, stroke.baseBrush, base.inputToolType)
                ?: return stroke.mesh.triangleOutline()
            return if (stroke.transform.isIdentity) modeled else modeled.transformed(stroke.transform.toMatrix())
        }
    }
}

/**
 * Platform implementation of the stroke modeller.
 */
interface StrokeEngineDelegate {
    val isNative: Boolean
    fun start(brush: BrushConfig, strokeUnitLengthCm: Float)
    fun addInput(x: Float, y: Float, pressure: Float, timeMs: Long, toolType: InputToolType)
    fun finishInputs()
    fun currentMesh(brush: BrushConfig, points: List<StrokePoint>, toolType: InputToolType): StrokeMesh?

    /** Outline of the stroke modeled so far, or `null` when this modeller cannot give it. */
    fun currentOutline(): StrokeOutline? = null
    fun clear()
    fun release()
}

/**
 * Creates the best stroke modeller available on the current platform.
 */
internal expect fun createStrokeEngineDelegate(): StrokeEngineDelegate
