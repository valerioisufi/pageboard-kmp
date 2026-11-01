package io.github.valerioisufi.drawview.canvas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.ink.InkStrokeEngine

/**
 * Draws the stroke in progress apart from the committed content (see `docs/ink-latency.md`): the
 * gesture handler tells it when the stroke changes, and the layer takes the shape of the stroke from
 * the engine when it draws it.
 */
internal interface LiveInkLayer {
    /** The stroke in progress of [engine] changed: drawn with [color] (ARGB) on the page [pageIndex]. */
    fun strokeChanged(engine: InkStrokeEngine, color: Int, pageIndex: Int)

    /** No stroke in progress any more: drawn into the tiles, or given up. */
    fun strokeEnded()

    /** Draws the stroke where the layer draws in the frames of Compose, over the committed content. */
    fun draw(scope: DrawScope, controller: DrawViewController)
}

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

    override fun strokeChanged(engine: InkStrokeEngine, color: Int, pageIndex: Int) {
        this.engine = engine
        this.color = color
        this.pageIndex = pageIndex
        tick++
    }

    override fun strokeEnded() {
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
