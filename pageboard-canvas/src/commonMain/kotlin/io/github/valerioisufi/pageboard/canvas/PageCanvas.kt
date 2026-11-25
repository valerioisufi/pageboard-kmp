package io.github.valerioisufi.pageboard.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
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
import io.github.valerioisufi.pageboard.canvas.controller.PageboardController
import io.github.valerioisufi.pageboard.canvas.controller.ProtractorGuide
import io.github.valerioisufi.pageboard.canvas.controller.RulerGuide
import io.github.valerioisufi.pageboard.canvas.controller.ShapeDraft
import io.github.valerioisufi.pageboard.canvas.geometry.mapRect
import io.github.valerioisufi.pageboard.canvas.geometry.toCompose
import io.github.valerioisufi.pageboard.canvas.geometry.toComposeMatrix
import io.github.valerioisufi.pageboard.canvas.render.drawVectors
import io.github.valerioisufi.pageboard.canvas.tool.Tool
import io.github.valerioisufi.pageboard.ink.InkStrokeEngine
import io.github.valerioisufi.pageboard.model.TextElement
import io.github.valerioisufi.pageboard.model.geometry.Matrix2D
import io.github.valerioisufi.pageboard.model.geometry.Rect as MmRect
import io.github.valerioisufi.pageboard.model.shape.ShapeGeometry
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.flow.first

/**
 * Full-featured vector drawing canvas: multi-resolution tiles for the committed content, live
 * layers for the stroke in progress and the selection, Google Ink stroke modelling and
 * framerate-independent camera spring physics.
 *
 * The stroke in progress and the cursors are drawn in a canvas of their own over the content
 * (see [LiveInkLayer]): a change of the stroke does not draw the tiles again.
 */
@Composable
public fun PageCanvas(
    controller: PageboardController,
    modifier: Modifier = Modifier,
    engine: InkStrokeEngine = remember { InkStrokeEngine() }
) {
    PageCanvas(controller, modifier, engine, rememberInkPlatform(controller))
}

/** [PageCanvas] with the ink [platform] given: the one of this platform, or one of the tests. */
@Composable
internal fun PageCanvas(
    controller: PageboardController,
    modifier: Modifier,
    engine: InkStrokeEngine,
    platform: InkPlatform
) {
    // Set before the canvas is laid out, so that the first layout is at the density of the screen
    val density = LocalDensity.current.density
    if (controller.viewport.density != density) {
        controller.viewport.density = density
    }

    val live = remember { CanvasLiveState() }
    val ink = rememberLiveInkLayer(controller)
    val gestures = remember(controller, engine, platform) { CanvasGestureHandler(controller, engine, live, ink, platform) }
    val labels = rememberTextMeasurer()
    val collaboratorsOverlay = remember { CollaboratorsOverlay() }

    DisposableEffect(engine) {
        onDispose { engine.release() }
    }

    DisposableEffect(controller, gestures) {
        controller.canvasGestures = gestures
        onDispose { if (controller.canvasGestures === gestures) controller.canvasGestures = null }
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerHoverIcon(pointerIcon)
            .clipToBounds()
            .onSizeChanged { size ->
                if (size.width > 0 && size.height > 0) {
                    controller.viewport.setSize(size.width.toFloat(), size.height.toFloat())
                }
            }
            .inkInput(platform) { fingerDraws(controller) }
            .trackpadInput(gestures::onTrackpadPinch, gestures::onTrackpadScrollPhase)
            .canvasGestureExclusion()
            .pointerInput(controller, engine) {
                with(gestures) { run() }
            }
    ) {
        // Each canvas in a layer of its own: drawing one again leaves the other as it was recorded
        Canvas(Modifier.fillMaxSize().graphicsLayer()) { drawContent(controller, ink, animationTick, collaboratorsOverlay, labels) }
        Canvas(Modifier.fillMaxSize().graphicsLayer()) { drawLive(controller, live, ink, labels) }
        ink.Content()
    }
}

/** The committed content of the canvas and what is drawn over it but the live state. */
private fun DrawScope.drawContent(
    controller: PageboardController,
    ink: LiveInkLayer,
    animationTick: Long,
    collaboratorsOverlay: CollaboratorsOverlay,
    labels: TextMeasurer
) {
    // Observe every source of invalidation
    @Suppress("UNUSED_VARIABLE") val v = controller.version
    @Suppress("UNUSED_VARIABLE") val camera = controller.viewport.camera.moveCount
    @Suppress("UNUSED_VARIABLE") val tick = animationTick
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
    ink.tilesDrawn()

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

    // 4c. The places found by the search in the document
    FindOverlay.draw(this, controller)

    // 5. What the others are doing (texts written, elements moved, strokes drawn)
    collaboratorsOverlay.drawWork(this, controller, labels)
    collaboratorsOverlay.drawInk(this, controller, viewport)

    // 6. Pointers of the others, with their names
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

/**
 * The live state over the content: the stroke in progress (when its layer draws in Compose), the
 * cursor of the eraser and of the pens, the box of a new text.
 */
private fun DrawScope.drawLive(controller: PageboardController, live: CanvasLiveState, ink: LiveInkLayer, labels: TextMeasurer) {
    @Suppress("UNUSED_VARIABLE") val liveTick = live.tick
    ink.draw(this, controller)

    // Eraser cursor, and where the ink of a pen would land
    live.eraserCursor?.let { cursor ->
        if (controller.isDrawingMode) {
            drawCircle(Color(0x33FF8DA1), radius = live.eraserRadiusPx, center = cursor)
            drawCircle(Color(0xCCFF8DA1), radius = live.eraserRadiusPx, center = cursor, style = Stroke(width = 2f))
        }
    }
    live.inkCursor?.let { cursor ->
        if (controller.isDrawingMode && controller.toolManager.isInkTool) {
            val inkColor = Color(live.inkCursorColor)
            // At least a dot of a few pixels, ringed so that it shows on any background
            val radius = live.inkCursorRadiusPx.coerceAtLeast(1.5f)
            drawCircle(inkColor.copy(alpha = inkColor.alpha * 0.6f), radius = radius, center = cursor)
            drawCircle(Color(0x99FFFFFF), radius = radius + 1f, center = cursor, style = Stroke(width = 1f))
        }
    }

    // Box of a new text being drawn with the text tool
    live.textBoxDraft?.let { box ->
        val primary = Color(controller.themeColors.primaryColor)
        drawRect(primary.copy(alpha = FrameStyle.FILL_ALPHA), topLeft = box.topLeft, size = box.size)
        drawRect(
            primary,
            topLeft = box.topLeft,
            size = box.size,
            style = Stroke(width = FrameStyle.STROKE_DP * controller.viewport.density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f)))
        )
    }

    controller.shapeInserter.draft?.let { drawShapeDraft(controller, it, labels) }
    controller.shapeEditing.handleDraft?.let { draft ->
        drawShapeDraft(controller, draft, labels)
        val mmToScreen = controller.viewport.pageMmToScreen(draft.pageIndex)
        val connectors = controller.shapeEditing.connectorDrafts
        if (mmToScreen != null && connectors.isNotEmpty()) {
            drawIntoCanvas { canvas ->
                for ((shapeValue, box) in connectors) {
                    if (box.width <= 0f || box.height <= 0f) continue
                    val boxToScreen = Matrix2D()
                        .postRotate(box.rotation, box.width / 2f, box.height / 2f)
                        .postTranslate(box.x, box.y)
                        .postConcat(mmToScreen)
                    canvas.save()
                    canvas.concat(boxToScreen.toComposeMatrix())
                    canvas.drawVectors(ShapeGeometry.drawing(shapeValue, box.width, box.height))
                    canvas.restore()
                }
            }
        }
    }

    controller.geometryGuides.ruler?.let { drawRulerGuide(controller, it, labels) }
    controller.geometryGuides.protractor?.let { drawProtractorGuide(controller, it, labels) }
}

private fun DrawScope.drawRulerGuide(controller: PageboardController, ruler: RulerGuide, labels: TextMeasurer) {
    val density = controller.viewport.density
    val primary = Color(controller.themeColors.primaryColor)
    val stroke = 1.5f * density
    val pageIndex = controller.viewport.mostVisiblePageIndex() ?: 0
    val pxPerMm = controller.viewport.pixelsPerMm(pageIndex).coerceAtLeast(1f)
    translate(ruler.centerX, ruler.centerY) {
        rotate(ruler.angleDeg, pivot = Offset.Zero) {
            val halfW = ruler.lengthPx / 2f
            val halfH = ruler.thicknessPx / 2f
            drawRoundRect(
                color = Color(0xCCFFFFFF),
                topLeft = Offset(-halfW, -halfH),
                size = Size(ruler.lengthPx, ruler.thicknessPx),
                cornerRadius = CornerRadius(4f * density)
            )
            drawRoundRect(
                color = primary.copy(alpha = 0.85f),
                topLeft = Offset(-halfW, -halfH),
                size = Size(ruler.lengthPx, ruler.thicknessPx),
                cornerRadius = CornerRadius(4f * density),
                style = Stroke(width = stroke)
            )
            var mm = 0
            while (mm * pxPerMm <= ruler.lengthPx - 12f * density) {
                val x = -halfW + 6f * density + mm * pxPerMm
                val tickH = when {
                    mm % 10 == 0 -> 14f * density
                    mm % 5 == 0 -> 9f * density
                    else -> 5f * density
                }
                drawLine(Color(0xFF444444), Offset(x, -halfH), Offset(x, -halfH + tickH), strokeWidth = 1f * density)
                drawLine(Color(0xFF444444), Offset(x, halfH), Offset(x, halfH - tickH), strokeWidth = 1f * density)
                mm++
            }
            // Rotation handles at both ends
            val handleOffset = halfW - 18f * density
            for (hx in listOf(-handleOffset, handleOffset)) {
                drawCircle(Color.White, radius = 7f * density, center = Offset(hx, 0f))
                drawCircle(primary, radius = 7f * density, center = Offset(hx, 0f), style = Stroke(width = stroke))
            }
        }
    }
    val degText = "${ruler.angleDeg.roundToInt()}°"
    val badge = labels.measure(degText, TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium))
    val pad = 4f * density
    drawRoundRect(
        color = Color(0xD9303030),
        topLeft = Offset(ruler.centerX - badge.size.width / 2f - pad, ruler.centerY - badge.size.height / 2f - pad / 2f),
        size = Size(badge.size.width + pad * 2f, badge.size.height + pad),
        cornerRadius = CornerRadius(4f * density)
    )
    drawText(badge, topLeft = Offset(ruler.centerX - badge.size.width / 2f, ruler.centerY - badge.size.height / 2f))
}

private fun DrawScope.drawProtractorGuide(controller: PageboardController, protractor: ProtractorGuide, labels: TextMeasurer) {
    val density = controller.viewport.density
    val primary = Color(controller.themeColors.primaryColor)
    val stroke = 1.5f * density
    val r = protractor.radiusPx
    translate(protractor.centerX, protractor.centerY) {
        rotate(protractor.angleDeg, pivot = Offset.Zero) {
            drawArc(
                color = Color(0xCCFFFFFF),
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = true,
                topLeft = Offset(-r, -r),
                size = Size(r * 2f, r * 2f)
            )
            drawArc(
                color = primary.copy(alpha = 0.85f),
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = true,
                topLeft = Offset(-r, -r),
                size = Size(r * 2f, r * 2f),
                style = Stroke(width = stroke)
            )
            for (deg in 0..180 step 5) {
                val rad = deg * PI.toFloat() / 180f
                val cosA = cos(rad)
                val sinA = sin(rad)
                val tickLen = when {
                    deg % 45 == 0 -> 14f * density
                    deg % 15 == 0 -> 10f * density
                    else -> 5f * density
                }
                val outer = Offset(r * cosA, -r * sinA)
                val inner = Offset((r - tickLen) * cosA, -(r - tickLen) * sinA)
                drawLine(Color(0xFF444444), outer, inner, strokeWidth = 1f * density)
            }
            // Center crosshair and top rotation knob
            drawLine(primary, Offset(-8f * density, 0f), Offset(8f * density, 0f), strokeWidth = stroke)
            drawLine(primary, Offset(0f, -8f * density), Offset(0f, 4f * density), strokeWidth = stroke)
            drawCircle(Color.White, radius = 7f * density, center = Offset(0f, -r))
            drawCircle(primary, radius = 7f * density, center = Offset(0f, -r), style = Stroke(width = stroke))
        }
    }
    val degText = "${protractor.angleDeg.roundToInt()}°"
    val badge = labels.measure(degText, TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium))
    val pad = 4f * density
    drawRoundRect(
        color = Color(0xD9303030),
        topLeft = Offset(protractor.centerX - badge.size.width / 2f - pad, protractor.centerY - protractor.radiusPx * 0.45f - badge.size.height / 2f),
        size = Size(badge.size.width + pad * 2f, badge.size.height + pad),
        cornerRadius = CornerRadius(4f * density)
    )
    drawText(badge, topLeft = Offset(protractor.centerX - badge.size.width / 2f, protractor.centerY - protractor.radiusPx * 0.45f - badge.size.height / 2f + pad / 2f))
}

/**
 * The shape being drawn with the shapes tool, as it will be, the anchors of the other shapes near
 * the pointer (the one its end snapped to ringed) and its size by the pointer.
 */
private fun DrawScope.drawShapeDraft(controller: PageboardController, draft: ShapeDraft, labels: TextMeasurer) {
    val mmToScreen = controller.viewport.pageMmToScreen(draft.pageIndex) ?: return
    val box = draft.box
    if (box.width > 0f && box.height > 0f) {
        val boxToScreen = Matrix2D()
            .postRotate(box.rotation, box.width / 2f, box.height / 2f)
            .postTranslate(box.x, box.y)
            .postConcat(mmToScreen)
        drawIntoCanvas { canvas ->
            canvas.save()
            canvas.concat(boxToScreen.toComposeMatrix())
            canvas.drawVectors(ShapeGeometry.drawing(draft.value, box.width, box.height))
            canvas.restore()
        }
    }
    val primary = Color(controller.themeColors.primaryColor)
    val density = controller.viewport.density
    drawShapeSize(draft, mmToScreen.mapPoint(draft.end.x, draft.end.y).let { Offset(it.x, it.y) }, labels, primary)
    if (draft.anchors.isEmpty() && draft.anchor == null) return
    for (anchor in draft.anchors) {
        val point = mmToScreen.mapPoint(anchor.x, anchor.y)
        drawCircle(Color.White, radius = ANCHOR_DOT_DP * density + density, center = Offset(point.x, point.y))
        drawCircle(primary.copy(alpha = 0.7f), radius = ANCHOR_DOT_DP * density, center = Offset(point.x, point.y))
    }
    draft.anchor?.let { anchor ->
        val point = mmToScreen.mapPoint(anchor.x, anchor.y)
        drawCircle(primary, radius = ANCHOR_RING_DP * density, center = Offset(point.x, point.y), style = Stroke(width = 2f * density))
    }
}

/**
 * The size of the shape being drawn below and to the right of [pointer] (above it near the
 * bottom of the canvas): `42 × 30 mm`, or `56 mm · 30°` for a line; in the color of the theme
 * when a constraint holds (a square, an angle of 15°, an anchor).
 */
private fun DrawScope.drawShapeSize(draft: ShapeDraft, pointer: Offset, labels: TextMeasurer, primary: Color) {
    val text = if (draft.value.kind.isLine) {
        "${millimeters(draft.widthMm)} mm · ${draft.angleDegrees.roundToInt() % 360}°"
    } else {
        "${millimeters(draft.widthMm)} × ${millimeters(draft.heightMm)} mm"
    }
    val held = draft.isRegular || draft.isAngleSnapped || draft.anchor != null
    val label = labels.measure(text, TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium))
    val padding = 6.dp.toPx()
    val gap = 16.dp.toPx()
    val width = label.size.width + padding * 2
    val height = label.size.height + padding
    val left = (pointer.x + gap).coerceAtMost(size.width - width).coerceAtLeast(0f)
    val top = (if (pointer.y + gap + height > size.height) pointer.y - gap - height else pointer.y + gap).coerceAtLeast(0f)
    drawRoundRect(
        color = if (held) primary else Color(0xE6303030),
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(6.dp.toPx())
    )
    drawText(label, topLeft = Offset(left + padding, top + padding / 2))
}

/** [mm] with one decimal under 10 mm, whole above. */
private fun millimeters(mm: Float): String =
    if (mm < 10f) ((mm * 10f).roundToInt() / 10f).toString() else mm.roundToInt().toString()

/** The dots of the anchors of the shapes, and the ring of the one snapped to, on the screen. */
private const val ANCHOR_DOT_DP = 3f
private const val ANCHOR_RING_DP = 8f

/** The text of a pending placement in [box] (page millimeters), in a dashed frame. */
private fun DrawScope.drawPlacementPreview(
    controller: PageboardController,
    preview: TextElement,
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

/**
 * Whether a finger touching the canvas of [controller] now draws a stroke: a pen chosen, the
 * fingers not left to pan (stylus-only mode, Quick Pan).
 */
private fun fingerDraws(controller: PageboardController): Boolean =
    controller.isDrawingMode && controller.toolManager.isInkTool && !controller.isStylusOnlyMode && !controller.isQuickPanEnabled
