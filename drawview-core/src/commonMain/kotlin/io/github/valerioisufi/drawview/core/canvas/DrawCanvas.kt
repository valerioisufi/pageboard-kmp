package io.github.valerioisufi.drawview.core.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.drawview.core.controller.Collaborator
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.core.engine.InkStrokeEngine
import io.github.valerioisufi.drawview.core.geometry.Matrix2D
import io.github.valerioisufi.drawview.core.model.DrawText

/**
 * Full-featured vector drawing canvas: multi-resolution tiles for the committed content, live
 * layers for the stroke in progress and the selection, Google Ink stroke modelling and
 * framerate-independent camera spring physics.
 */
@Composable
fun DrawCanvas(
    controller: DrawViewController,
    modifier: Modifier = Modifier,
    engine: InkStrokeEngine = remember { InkStrokeEngine() }
) {
    val density = LocalDensity.current.density
    LaunchedEffect(density) {
        controller.density = density
    }

    val live = remember { CanvasLiveState() }
    val gestures = remember(controller, engine) { CanvasGestureHandler(controller, engine, live) }
    val livePainter = remember { StrokeMeshPainter() }
    val labels = rememberTextMeasurer()

    DisposableEffect(engine) {
        onDispose { engine.release() }
    }

    // Physics animation loop and auto-scroll while dragging near the edges
    var animationTick by remember { mutableStateOf(0L) }
    LaunchedEffect(controller) {
        var lastNanos = 0L
        while (true) {
            withFrameNanos { nowNanos ->
                if (lastNanos > 0L) {
                    val dtMillis = ((nowNanos - lastNanos) / 1_000_000L).coerceAtLeast(1L)
                    if (controller.cameraPhysics.isAnimating()) {
                        controller.cameraPhysics.update(dtMillis)
                        animationTick = nowNanos
                    }
                    if (live.autoScrollPerFrame != 0f) {
                        gestures.onAutoScrollFrame()
                        animationTick = nowNanos
                    }
                }
                lastNanos = nowNanos
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { size ->
                if (size.width > 0 && size.height > 0) {
                    controller.setViewport(size.width.toFloat(), size.height.toFloat())
                }
            }
            .unbufferedStylusInput()
            .trackpadInput(gestures::onTrackpadPinch, gestures::onTrackpadScrollPhase)
            .pointerInput(controller, engine) {
                with(gestures) { run() }
            }
    ) {
        // Observe every source of invalidation
        @Suppress("UNUSED_VARIABLE") val v = controller.version
        @Suppress("UNUSED_VARIABLE") val camera = controller.cameraPhysics.moveCount
        @Suppress("UNUSED_VARIABLE") val tick = animationTick
        @Suppress("UNUSED_VARIABLE") val liveTick = live.tick
        @Suppress("UNUSED_VARIABLE") val selectionVersion = controller.selectionManager.selectionVersion
        val floatingPage = controller.pageManager.floatingPageRect
        val document = controller.document
        val theme = controller.themeColors

        val matrix = controller.getRenderMatrix(Matrix2D())
        val viewport = Rect(0f, 0f, size.width, size.height)
        val visible = controller.visiblePages()
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
                pageRects = controller.calcPage.pagesRectOnWindow,
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

        // 4. Live selection
        controller.selectionManager.currentSelection?.let { selection ->
            SelectionOverlay.draw(this, controller, selection, Color(theme.primaryColor))
        }

        // 4b. Text of the PDFs: passage being marked, selected text, selected mark
        TextMarkupOverlay.draw(this, controller, Color(theme.primaryColor))

        // 5. Strokes the others are drawing, then the one in progress here
        val collaborators = controller.collaborators
        for (collaborator in collaborators) {
            val mesh = collaborator.ink ?: continue
            val mmToScreen = controller.pageMmToScreen(document.pages.indexOfFirst { it.id == collaborator.inkPageId }) ?: continue
            drawIntoCanvas { canvas ->
                if (collaborator.inkAlpha < 1f) {
                    // Fading away once drawn
                    canvas.saveLayer(viewport, Paint().apply { alpha = collaborator.inkAlpha })
                    livePainter.draw(canvas, mesh, mmToScreen)
                    canvas.restore()
                } else {
                    livePainter.draw(canvas, mesh, mmToScreen)
                }
            }
        }
        live.liveMesh?.let { mesh ->
            val mmToScreen = controller.pageMmToScreen(live.livePageIndex)
            if (mmToScreen != null) {
                drawIntoCanvas { canvas -> livePainter.draw(canvas, mesh, mmToScreen) }
            }
        }

        // 6. Eraser cursor
        live.eraserCursor?.let { cursor ->
            if (controller.isDrawingMode) {
                drawCircle(Color(0x33FF8DA1), radius = live.eraserRadiusPx, center = cursor)
                drawCircle(Color(0xCCFF8DA1), radius = live.eraserRadiusPx, center = cursor, style = Stroke(width = 2f))
            }
        }

        // 6b. Pointers of the others, with their names
        for (collaborator in collaborators) {
            val mmToScreen = controller.pageMmToScreen(document.pages.indexOfFirst { it.id == collaborator.pageId }) ?: continue
            drawCollaboratorPointer(collaborator, mmToScreen.mapPoint(collaborator.position.x, collaborator.position.y), labels)
        }

        // 7. Floating page while reordering
        if (draggedPage != null && floatingPage != null) {
            val worldRect = controller.calcPage.pagesRectOnWindow.getOrNull(draggedIndex)
            drawPaperSheet(floatingPage, Color(draggedPage.background.backgroundColor), elevated = true)
            if (worldRect != null) {
                drawIntoCanvas { canvas ->
                    controller.tileManager.drawPageTiles(
                        canvas, draggedPage, worldRect, floatingPage, controller.cameraPhysics.getCurrentScale()
                    )
                }
            }
            drawRect(Color(theme.primaryColor), topLeft = floatingPage.topLeft, size = floatingPage.size, style = Stroke(width = 4f))
        }

        // 8. Element waiting to be placed, under the mouse or the hovering stylus
        val placement = controller.placementManager
        val placementRequest = placement.request
        val pointer = placement.pointer
        if (placementRequest != null && pointer != null) {
            placement.targetAt(pointer)?.let { (pageIndex, topLeft) ->
                drawPlacementPreview(controller, placementRequest.preview, pageIndex, topLeft, Color(theme.primaryColor))
            }
        }
    }
}

/** The pointer of another device at [point] (screen), with its name beside it. */
private fun DrawScope.drawCollaboratorPointer(collaborator: Collaborator, point: Offset, labels: TextMeasurer) {
    drawCircle(Color.White, radius = 7.dp.toPx(), center = point)
    drawCircle(collaborator.color, radius = 5.dp.toPx(), center = point)
    val label = labels.measure(collaborator.name, TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium))
    val padding = 4.dp.toPx()
    val topLeft = point + Offset(9.dp.toPx(), 7.dp.toPx())
    drawRoundRect(
        color = collaborator.color,
        topLeft = topLeft,
        size = Size(label.size.width + padding * 2, label.size.height + padding),
        cornerRadius = CornerRadius(6.dp.toPx())
    )
    drawText(label, topLeft = topLeft + Offset(padding, padding / 2))
}

/** The text of a pending placement at [topLeft] (page millimeters), in a dashed frame. */
private fun DrawScope.drawPlacementPreview(
    controller: DrawViewController,
    preview: DrawText,
    pageIndex: Int,
    topLeft: Offset,
    primary: Color
) {
    val mmToScreen = controller.pageMmToScreen(pageIndex) ?: return
    preview.x = topLeft.x
    preview.y = topLeft.y
    val frame = mmToScreen.mapRect(Rect(topLeft.x, topLeft.y, topLeft.x + preview.width, topLeft.y + preview.height))
    drawRect(primary.copy(alpha = 0.08f), topLeft = frame.topLeft, size = frame.size)
    drawRect(
        primary,
        topLeft = frame.topLeft,
        size = frame.size,
        style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f)))
    )
    controller.tileManager.tileRenderer.textAdapter?.let { adapter ->
        drawIntoCanvas { canvas -> adapter.drawText(canvas, preview, mmToScreen) }
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
