package io.github.valerioisufi.drawview.ink

import io.github.valerioisufi.drawview.model.BrushConfig
import io.github.valerioisufi.drawview.model.InputToolType
import io.github.valerioisufi.drawview.model.StrokeMesh
import io.github.valerioisufi.drawview.model.StrokeModeller
import io.github.valerioisufi.drawview.model.StrokePoint

/**
 * The strokes modeled by Google Ink ([InkStrokeEngine]), as the editor draws them; in Kotlin where
 * the native library cannot be loaded.
 */
object InkStrokeModeller : StrokeModeller {
    override fun buildMesh(points: List<StrokePoint>, brush: BrushConfig, toolType: InputToolType): StrokeMesh? =
        InkStrokeEngine.buildMesh(points, brush, toolType)
}
