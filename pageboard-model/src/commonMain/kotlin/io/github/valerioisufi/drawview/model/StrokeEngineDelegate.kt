package io.github.valerioisufi.drawview.model

/**
 * A stroke modeller fed with the inputs of a stroke as they arrive: [KotlinStrokeEngineDelegate]
 * in Kotlin, the one of Google Ink in `pageboard-ink`.
 */
public interface StrokeEngineDelegate {
    public val isNative: Boolean
    public fun start(brush: BrushConfig, strokeUnitLengthCm: Float)
    public fun addInput(x: Float, y: Float, pressure: Float, timeMs: Long, toolType: InputToolType)
    public fun finishInputs()
    public fun currentMesh(brush: BrushConfig, points: List<StrokePoint>, toolType: InputToolType): StrokeMesh?

    /** Outline of the stroke modeled so far, or `null` when this modeller cannot give it. */
    public fun currentOutline(): StrokeOutline? = null
    public fun clear()
    public fun release()
}
