package io.github.valerioisufi.drawview.core.engine

/**
 * The Google Ink C++ engine is not compiled for the web: strokes are tessellated in Kotlin.
 */
internal actual fun createStrokeEngineDelegate(): StrokeEngineDelegate = KotlinStrokeEngineDelegate()
