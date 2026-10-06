package io.github.valerioisufi.drawview.core.controller

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import io.github.valerioisufi.drawview.core.model.BrushConfig
import io.github.valerioisufi.drawview.core.model.InputToolType
import io.github.valerioisufi.drawview.core.model.StrokeMesh
import io.github.valerioisufi.drawview.core.model.StrokePoint

/**
 * What the user is doing on a page of the document: the pointer at ([x], [y]) in the millimeters
 * of the page [pageId], drawing the stroke [stroke] (`0`: not drawing) of the inputs [ink] (in the
 * same millimeters) with [brush], by [inputType].
 */
data class EditorActivity(
    val pageId: String,
    val x: Float,
    val y: Float,
    val stroke: Long = 0,
    val ink: List<StrokePoint> = emptyList(),
    val brush: BrushConfig? = null,
    val inputType: InputToolType = InputToolType.UNKNOWN
)

/**
 * Another device working on the document, as drawn over it (see
 * [DrawViewController.collaborators]): its pointer at [position] on the page [pageId] (page
 * millimeters; `null`: on no page) with its [name] and [color], and the stroke it is drawing
 * ([ink], on the page [inkPageId]), fading away once drawn ([inkAlpha]).
 */
data class Collaborator(
    val id: String,
    val name: String,
    val color: Color,
    val pageId: String?,
    val position: Offset,
    val ink: StrokeMesh? = null,
    val inkPageId: String? = null,
    val inkAlpha: Float = 1f
)
