package io.github.valerioisufi.pageboard.ink

import io.github.valerioisufi.pageboard.model.BrushConfig
import io.github.valerioisufi.pageboard.model.InputToolType
import io.github.valerioisufi.pageboard.model.StrokeMesh
import io.github.valerioisufi.pageboard.model.StrokeModeller
import io.github.valerioisufi.pageboard.model.StrokePoint

/**
 * The strokes modeled by Google Ink ([InkStrokeEngine]), as the editor draws them; in Kotlin where
 * the native library cannot be loaded.
 */
public object InkStrokeModeller : StrokeModeller {
    override fun buildMesh(points: List<StrokePoint>, brush: BrushConfig, toolType: InputToolType): StrokeMesh? =
        InkStrokeEngine.buildMesh(points, brush, toolType)
}
