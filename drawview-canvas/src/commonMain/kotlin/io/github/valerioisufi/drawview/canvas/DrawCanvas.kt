package io.github.valerioisufi.drawview.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.canvas.geometry.mapRect
import io.github.valerioisufi.drawview.canvas.geometry.toCompose
import io.github.valerioisufi.drawview.canvas.tool.Tool
import io.github.valerioisufi.drawview.ink.InkStrokeEngine
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import io.github.valerioisufi.drawview.model.geometry.Rect as MmRect
import kotlinx.coroutines.flow.first

/**
 * Full-featured vector drawing canvas: multi-resolution tiles for the committed content, live
 * layers for the stroke in progress and the selection, Google Ink stroke modelling and
 * framerate-independent camera spring physics.
 */
@Composable
public fun DrawCanvas(
    controller: DrawViewController,
    modifier: Modifier = Modifier,
    engine: InkStrokeEngine = remember { InkStrokeEngine() }
) {
    // Set before the canvas is laid out, so that the first layout is at the density of the screen
    val density = LocalDensity.current.density
    if (controller.viewport.density != density) {
        controller.viewport.density = density
    }

    val live = remember { CanvasLiveState() }
    val gestures = remember(controller, engine) { CanvasGestureHandler(controller, engine, live) }
    val livePainter = remember { StrokeMeshPainter() }
    val labels = rememberTextMeasurer()
    val collaboratorsOverlay = remember { CollaboratorsOverlay() }

    DisposableEffect(engine) {
        onDispose { engine.release() }
    }

    // Physics animation loop and auto-scroll while dragging near the edges. No frame is asked for
    // while nothing moves by itself: a page only looked at lets the device sleep
    var animationTick by remember { mutableStateOf(0L) }
    LaunchedEffect(controller) {
        val camera = controller.viewport.camera
        fun moving() = camera.isAnimating() || live.autoScrollPerFrame != 0f
        while (true) {
            snapshotFlow { camera.animationStarts to live.autoScrollPerFrame }.first { moving() }
            var lastNanos = 0L
            while (moving()) {
                withFrameNanos { nowNanos ->
                    // The first frame of a movement advances by one frame of a 60 Hz display
                    val dtMillis = if (lastNanos > 0L) ((nowNanos - lastNanos) / 1_000_000L).coerceAtLeast(1L) else 16L
                    if (camera.isAnimating()) {
                        camera.update(dtMillis)
                        animationTick = nowNanos
                    }
                    if (live.autoScrollPerFrame != 0f) {
                        gestures.onAutoScrollFrame()
                        animationTick = nowNanos
                    }
                    lastNanos = nowNanos
                }
            }
        }
    }

    // The cursor of the tool where a mouse hovers the pages: a crosshair for the pens, the eraser and
    // the lasso, an I-beam for the texts, a hand for the pan
    val pointerIcon = when {
        controller.placementManager.isActive -> PointerIcon.Crosshair
        // The frame of the text being written moves it
        controller.textEditorManager.isEditing && live.overEditedTextFrame -> PointerIcon.Hand
        !controller.isDrawingMode -> PointerIcon.Default
        controller.selectedTool == Tool.PAN -> PointerIcon.Hand
        controller.selectedTool == Tool.TEXT || controller.selectedTool == Tool.TEXT_MARKUP -> PointerIcon.Text
        else -> PointerIcon.Crosshair
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerHoverIcon(pointerIcon)
            .clipToBounds()
            .onSizeChanged { size ->
                if (size.width > 0 && size.height > 0) {
                    controller.viewport.setSize(size.width.toFloat(), size.height.toFloat())
                }
            }
            .unbufferedStylusInput()
            .trackpadInput(gestures::onTrackpadPinch, gestures::onTrackpadScrollPhase)
            .canvasGestureExclusion()
            .pointerInput(controller, engine) {
                with(gestures) { run() }
            }
    ) {
        // Observe every source of invalidation
        @Suppress("UNUSED_VARIABLE") val v = controller.version
        @Suppress("UNUSED_VARIABLE") val camera = controller.viewport.camera.moveCount
        @Suppress("UNUSED_VARIABLE") val tick = animationTick
        @Suppress("UNUSED_VARIABLE") val liveTick = live.tick
        @Suppress("UNUSED_VARIABLE") val selectionVersion = controller.selectionManager.selectionVersion
        val floatingPage = controller.pageManager.floatingPageRect
        val document = controller.document
        val theme = controller.themeColors

        val matrix = controller.viewport.getRenderMatrix(Matrix2D())
        val viewport = Rect(0f, 0f, size.width, size.height)
        val visible = controller.viewport.visiblePages()
        val draggedIndex = controller.pageManager.draggedPageIndex
        val draggedPage = document.pages.getOrNull(draggedIndex)?.takeIf { floatingPage != null }

        // 0. Desk background
        drawRect(Color(theme.backgroundColor))

        // 1. Paper sheets with their drop shadow (visible even before the tiles are ready)
        for (pageInfo in visible) {
            if (draggedPage != null && pageInfo.index == draggedIndex) continue
            val page = document.pages.getOrNull(pageInfo.index) ?: continue
            drawPaperSheet(pageInfo.rect, Color(page.background.backgroundColor))
        }

        // 2. Committed content (multi-resolution tiles)
        drawIntoCanvas { canvas ->
            controller.tileManager.drawTiles(
                canvas = canvas,
                cameraMatrix = matrix,
                viewportBounds = viewport,
                document = document,
                pageRects = controller.viewport.layout.pagesRectOnWindow,
                skipPageKey = draggedPage?.renderKey
            )
        }

        // 3. Drop slot of the page being dragged
        if (draggedPage != null) {
            visible.firstOrNull { it.index == draggedIndex }?.let { slot ->
                drawRect(Color(0x1E000000), topLeft = slot.rect.topLeft, size = slot.rect.size)
                drawRect(Color(theme.primaryColor), topLeft = slot.rect.topLeft, size = slot.rect.size, style = Stroke(width = 4f))
            }
        }

        // 4. Live selection, or the frame of the text being written (its field draws the text over it)
        SelectionOverlay.drawSettling(this, controller)
        controller.selectionManager.currentSelection?.let { selection ->
            SelectionOverlay.draw(this, controller, selection, Color(theme.primaryColor))
        }
        if (controller.textEditorManager.isEditing && controller.isDrawingMode) {
            SelectionOverlay.drawEditedText(this, controller, Color(theme.primaryColor))
        }

        // 4a. The guides of a box snapped to what is around it
        val snaps = controller.snapManager
        if (snaps.guides.isNotEmpty()) {
            controller.viewport.pageMmToScreen(snaps.guidesPageIndex)?.let { mmToScreen ->
                for (guide in snaps.guides) {
                    val start = if (guide.vertical) mmToScreen.mapPoint(guide.position, guide.from).toCompose() else mmToScreen.mapPoint(guide.from, guide.position).toCompose()
                    val end = if (guide.vertical) mmToScreen.mapPoint(guide.position, guide.to).toCompose() else mmToScreen.mapPoint(guide.to, guide.position).toCompose()
                    drawLine(Color(FrameStyle.GUIDE_COLOR), start, end, strokeWidth = FrameStyle.GUIDE_STROKE_DP * controller.viewport.density)
                }
            }
        }

        // 4b. Text of the PDFs: passage being marked, selected text, selected mark
        TextMarkupOverlay.draw(this, controller, Color(theme.primaryColor))

        // 5. What the others are doing (texts written, elements moved, strokes drawn), then the
        //    stroke in progress here
        collaboratorsOverlay.drawWork(this, controller, labels)
        collaboratorsOverlay.drawInk(this, controller, viewport)
        live.liveMesh?.let { mesh ->
            val mmToScreen = controller.viewport.pageMmToScreen(live.livePageIndex)
            if (mmToScreen != null) {
                drawIntoCanvas { canvas -> livePainter.draw(canvas, mesh, mmToScreen) }
                controller.inkLatencyMeter.frameDrawn()
            }
        }

        // 6. Eraser cursor, and where the ink of a pen would land
        live.eraserCursor?.let { cursor ->
            if (controller.isDrawingMode) {
                drawCircle(Color(0x33FF8DA1), radius = live.eraserRadiusPx, center = cursor)
                drawCircle(Color(0xCCFF8DA1), radius = live.eraserRadiusPx, center = cursor, style = Stroke(width = 2f))
            }
        }
        live.inkCursor?.let { cursor ->
            if (controller.isDrawingMode && controller.toolManager.isInkTool) {
                val ink = Color(live.inkCursorColor)
                // At least a dot of a few pixels, ringed so that it shows on any background
                val radius = live.inkCursorRadiusPx.coerceAtLeast(1.5f)
                drawCircle(ink.copy(alpha = ink.alpha * 0.6f), radius = radius, center = cursor)
                drawCircle(Color(0x99FFFFFF), radius = radius + 1f, center = cursor, style = Stroke(width = 1f))
            }
        }

        // 6b. Pointers of the others, with their names
        collaboratorsOverlay.drawPointers(this, controller, labels)

        // 7. Floating page while reordering
        if (draggedPage != null && floatingPage != null) {
            val worldRect = controller.viewport.layout.pagesRectOnWindow.getOrNull(draggedIndex)
            drawPaperSheet(floatingPage, Color(draggedPage.background.backgroundColor), elevated = true)
            if (worldRect != null) {
                drawIntoCanvas { canvas ->
                    controller.tileManager.drawPageTiles(
                        canvas, draggedPage, worldRect, floatingPage, controller.viewport.camera.getCurrentScale()
                    )
                }
            }
            drawRect(Color(theme.primaryColor), topLeft = floatingPage.topLeft, size = floatingPage.size, style = Stroke(width = 4f))
        }

        // 7b. Box of a new text being drawn with the text tool
        live.textBoxDraft?.let { box ->
            drawRect(Color(theme.primaryColor).copy(alpha = FrameStyle.FILL_ALPHA), topLeft = box.topLeft, size = box.size)
            drawRect(
                Color(theme.primaryColor),
                topLeft = box.topLeft,
                size = box.size,
                style = Stroke(width = FrameStyle.STROKE_DP * controller.viewport.density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f)))
            )
        }

        // 8. Texts waiting to be placed: where they would go (the free space of their pages faintly
        // shown), or under the mouse or the hovering stylus when no place is proposed
        val placement = controller.placementManager
        val placementRequest = placement.request
        if (placementRequest != null) {
            val primary = Color(theme.primaryColor)
            for ((pageIndex, runs) in placement.freeSpace) {
                val mmToScreen = controller.viewport.pageMmToScreen(pageIndex) ?: continue
                for (run in runs) {
                    val frame = mmToScreen.mapRect(Rect(run.left, run.top, run.right, run.bottom))
                    drawRect(primary.copy(alpha = 0.05f), topLeft = frame.topLeft, size = frame.size)
                }
            }
            val spots = placement.spots
            for ((index, item) in placementRequest.items.withIndex()) {
                val spot = spots.getOrNull(index)
                if (spot != null) {
                    if (!spot.newPageAfter) drawPlacementPreview(controller, item.preview, spot.pageIndex, spot.box, primary)
                    continue
                }
                val pointer = placement.pointer ?: continue
                placement.targetAt(pointer)?.let { (pageIndex, topLeft) ->
                    val box = MmRect(topLeft.x, topLeft.y, topLeft.x + item.preview.width, topLeft.y + item.preview.height)
                    drawPlacementPreview(controller, item.preview, pageIndex, box, primary)
                }
            }
        }
    }
}

/** The text of a pending placement in [box] (page millimeters), in a dashed frame. */
private fun DrawScope.drawPlacementPreview(
    controller: DrawViewController,
    preview: DrawText,
    pageIndex: Int,
    box: MmRect,
    primary: Color
) {
    val mmToScreen = controller.viewport.pageMmToScreen(pageIndex) ?: return
    preview.x = box.left
    preview.y = box.top
    if (preview.width != box.width || preview.height != box.height) {
        preview.width = box.width
        preview.height = box.height
        preview.markDirty()
    }
    val frame = mmToScreen.mapRect(Rect(box.left, box.top, box.right, box.bottom))
    drawRect(primary.copy(alpha = 0.08f), topLeft = frame.topLeft, size = frame.size)
    drawRect(
        primary,
        topLeft = frame.topLeft,
        size = frame.size,
        style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f)))
    )
    controller.tileManager.tileRenderer.textAdapter?.let { adapter ->
        drawIntoCanvas { canvas -> adapter.drawText(canvas, preview, mmToScreen, controller.paperOf(pageIndex)) }
    }
}

private fun DrawScope.drawPaperSheet(rect: Rect, paper: Color, elevated: Boolean = false) {
    val shadowOffset = if (elevated) 10f else 3f
    val layers = if (elevated) 4 else 2
    for (i in layers downTo 1) {
        val spread = i * (if (elevated) 4f else 2f)
        drawRect(
            color = Color.Black.copy(alpha = 0.035f),
            topLeft = Offset(rect.left - spread / 2f, rect.top - spread / 2f + shadowOffset),
            size = Size(rect.width + spread, rect.height + spread)
        )
    }
    drawRect(color = paper, topLeft = rect.topLeft, size = rect.size)
}
