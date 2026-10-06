package io.github.valerioisufi.drawview.core.controller

import io.github.valerioisufi.drawview.core.model.StrokePoint

/**
 * What the user is doing on a page of the document: the pointer at ([x], [y]) in the millimeters
 * of the page [pageId], drawing the stroke of the inputs [ink] (in the same millimeters) with
 * [color] and [size] ([ink] empty: not drawing).
 */
data class EditorActivity(
    val pageId: String,
    val x: Float,
    val y: Float,
    val ink: List<StrokePoint> = emptyList(),
    val color: Int = 0,
    val size: Float = 0f
)
