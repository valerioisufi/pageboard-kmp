package io.github.valerioisufi.pageboard.model

/**
 * Models a whole stroke from its inputs: the triangles drawn and therefore the bounds of the
 * stroke. A document models with its [DrawDocument.strokeModeller] the strokes it receives as
 * data (loaded, from another device, put back by undo).
 *
 * The meshes are local: the inputs are what is stored and synchronized, so two devices modeling
 * with different modellers still end up with the same document.
 *
 * It is called from several threads at once: the stores model the strokes of a document on every
 * core as they load it.
 */
public fun interface StrokeModeller {
    /** The mesh of the stroke of [points] drawn with [brush], or `null` when there is none. */
    public fun buildMesh(points: List<StrokePoint>, brush: BrushConfig, toolType: InputToolType): StrokeMesh?
}

/**
 * The strokes modeled in Kotlin ([KotlinStrokeEngineDelegate]), without any native library: the
 * modeller of the documents of the model alone; the stores and the editors use the one of Google
 * Ink (`InkStrokeModeller` of `pageboard-ink`).
 */
public object KotlinStrokeModeller : StrokeModeller {
    override fun buildMesh(points: List<StrokePoint>, brush: BrushConfig, toolType: InputToolType): StrokeMesh? {
        if (points.isEmpty()) return null
        val delegate = KotlinStrokeEngineDelegate()
        return try {
            delegate.start(brush, STROKE_UNIT_LENGTH_CM_MM)
            for (p in points) delegate.addInput(p.x, p.y, p.pressure, p.timestampMs, toolType)
            delegate.finishInputs()
            delegate.currentMesh(brush, points, toolType)
        } finally {
            delegate.release()
        }
    }
}

/** Physical length of one stroke unit: the strokes are modeled in page millimeters. */
public const val STROKE_UNIT_LENGTH_CM_MM: Float = 0.1f
