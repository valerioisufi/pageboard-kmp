package io.github.valerioisufi.drawview.model

/**
 * A stroke modeller fed with the inputs of a stroke as they arrive: [KotlinStrokeEngineDelegate]
 * in Kotlin, the one of Google Ink in `drawview-ink`.
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
