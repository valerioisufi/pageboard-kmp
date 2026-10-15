package io.github.valerioisufi.drawview.canvas.controller

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import io.github.valerioisufi.drawview.model.BrushConfig
import io.github.valerioisufi.drawview.model.DrawElement
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.InputToolType
import io.github.valerioisufi.drawview.model.SelectionActivity
import io.github.valerioisufi.drawview.model.StrokeMesh
import io.github.valerioisufi.drawview.model.StrokePoint
import io.github.valerioisufi.drawview.model.change.TextData
import io.github.valerioisufi.drawview.model.geometry.AffineTransform

/**
 * What the user is doing on the document, for the others working on it:
 * - the pointer at ([x], [y]) in the millimeters of the page [pageId] (`null`: on no page),
 *   drawing the stroke [stroke] (`0`: not drawing) of the inputs [ink] (in the same millimeters)
 *   with [brush], by [inputType]; [strokeCancelled]: the last stroke was given up (e.g. for a
 *   gesture), not added to the document;
 * - the text being written ([text], on the page [textPageId]: its content as it is now);
 * - the elements selected ([selection]), and how they are being moved.
 */
data class EditorActivity(
    val pageId: String? = null,
    val x: Float = 0f,
    val y: Float = 0f,
    val stroke: Long = 0,
    val ink: List<StrokePoint> = emptyList(),
    val brush: BrushConfig? = null,
    val inputType: InputToolType = InputToolType.UNKNOWN,
    val strokeCancelled: Boolean = false,
    val text: TextData? = null,
    val textPageId: String? = null,
    val selection: SelectionActivity? = null
) {
    /** Whether there is nothing to show. */
    val isIdle: Boolean get() = pageId == null && !strokeCancelled && text == null && selection == null
}

/**
 * Another device working on the document, as drawn over it (see
 * [DrawViewController.collaborators]), with its [name] and [color]:
 * - its pointer at [position] on the page [pageId] (page millimeters; `null`: on no page);
 * - the stroke it is drawing ([ink], on the page [inkPageId]), fading away once drawn
 *   ([inkAlpha]);
 * - the text it is writing ([text], on the page [textPageId]);
 * - the elements it selected ([selection], on the page [selectionPageId]), moved by
 *   [selectionTransform] while it moves them (`null`: not moving).
 */
data class Collaborator(
    val id: String,
    val name: String,
    val color: Color,
    val pageId: String?,
    val position: Offset,
    val ink: StrokeMesh? = null,
    val inkPageId: String? = null,
    val inkAlpha: Float = 1f,
    val text: DrawText? = null,
    val textPageId: String? = null,
    val selection: List<DrawElement> = emptyList(),
    val selectionPageId: String? = null,
    val selectionTransform: AffineTransform? = null
)
