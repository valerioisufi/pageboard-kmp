package io.github.valerioisufi.drawview.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.ink.InkStrokeEngine
import io.github.valerioisufi.drawview.model.StrokeMesh

/**
 * Draws the stroke in progress apart from the committed content (see `docs/plans/ink-latency.md`): the
 * gesture handler tells it when the stroke changes, and the layer takes the shape of the stroke from
 * the engine when it draws it.
 */
internal interface LiveInkLayer {
    /**
     * The stroke in progress of [engine] changed: drawn with [color] (ARGB) on the page [pageIndex].
     * [replaced]: it is another stroke now (the shape of the stroke drawn), nothing is left of the
     * one before.
     */
    fun strokeChanged(engine: InkStrokeEngine, color: Int, pageIndex: Int, replaced: Boolean = false)

    /**
     * No stroke in progress any more: [finished], the stroke that the tiles draw from now on, or
     * given up (`null`).
     */
    fun strokeEnded(finished: StrokeMesh? = null)

    /** Draws the stroke where the layer draws in the frames of Compose, over the committed content. */
    fun draw(scope: DrawScope, controller: DrawViewController)

    /** The frame being recorded has drawn the tiles, with the strokes committed so far. */
    fun tilesDrawn() = Unit

    /** What the layer puts over the canvas to draw the stroke, if anything but the frames of Compose. */
    @Composable
    fun Content() = Unit
}

/** The layer of the stroke in progress of this platform. */
@Composable
internal expect fun rememberLiveInkLayer(controller: DrawViewController): LiveInkLayer

/**
 * The stroke in progress drawn in the frames of Compose, in a canvas of its own over the tiles: a
 * change of the stroke draws again only that canvas, and the shape is taken from the engine once
 * per frame, not once per input.
 */
internal class ComposeLiveInkLayer : LiveInkLayer {
    /** Incremented whenever the stroke changes: read by the canvas that draws it. */
    private var tick by mutableIntStateOf(0)
    private var engine: InkStrokeEngine? = null
    private var color = 0
    private var pageIndex = -1
    private val painter = StrokeMeshPainter()

    override fun strokeChanged(engine: InkStrokeEngine, color: Int, pageIndex: Int, replaced: Boolean) {
        this.engine = engine
        this.color = color
        this.pageIndex = pageIndex
        tick++
    }

    override fun strokeEnded(finished: StrokeMesh?) {
        engine = null
        pageIndex = -1
        tick++
    }

    override fun draw(scope: DrawScope, controller: DrawViewController) {
        @Suppress("UNUSED_VARIABLE") val observed = tick
        val engine = engine ?: return
        val mmToScreen = controller.viewport.pageMmToScreen(pageIndex) ?: return
        scope.drawIntoCanvas { canvas ->
            val outline = engine.currentOutline()
            if (outline != null) {
                painter.drawOutline(canvas, outline, Color(color), mmToScreen)
            } else {
                // The Kotlin tessellator gives triangles only
                engine.currentMesh()?.let { painter.draw(canvas, it, mmToScreen) }
            }
        }
        controller.inkLatencyMeter.frameDrawn()
    }
}
