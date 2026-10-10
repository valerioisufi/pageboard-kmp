package io.github.valerioisufi.drawview.core.canvas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.core.controller.EditorActivity
import io.github.valerioisufi.drawview.core.geometry.toCompose
import io.github.valerioisufi.drawview.core.model.Tool
import io.github.valerioisufi.drawview.ink.InkStrokeEngine
import io.github.valerioisufi.drawview.model.BrushConfig
import io.github.valerioisufi.drawview.model.InputToolType
import io.github.valerioisufi.drawview.model.StrokeMesh
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp

/**
 * Live (not yet committed) state of the canvas, drawn on top of the tiles.
 */
class CanvasLiveState {
    /** Incremented whenever the live layer changes. */
    var tick by mutableIntStateOf(0)
        private set

    var liveMesh: StrokeMesh? = null
        private set
    var livePageIndex: Int = -1
        private set

    /** Eraser cursor (screen pixels) and radius in pixels. */
    var eraserCursor: Offset? = null
        private set
    var eraserRadiusPx: Float = 0f
        private set

    /**
     * Vertical auto-scroll speed (pixels per frame) while dragging near the viewport edges:
     * observable, so that the canvas asks for frames only while it scrolls.
     */
    var autoScrollPerFrame by mutableFloatStateOf(0f)

    /** Whether a hovering pointer is on the frame of the text being written (which moves it). */
    var overEditedTextFrame by mutableStateOf(false)

    /** The box of a new text being drawn with the text tool (screen pixels), if any. */
    var textBoxDraft by mutableStateOf<Rect?>(null)

    fun setLiveMesh(mesh: StrokeMesh?, pageIndex: Int) {
        liveMesh = mesh
        livePageIndex = pageIndex
        tick++
    }

    fun setEraserCursor(position: Offset?, radiusPx: Float) {
        if (eraserCursor == position && eraserRadiusPx == radiusPx) return
        eraserCursor = position
        eraserRadiusPx = radiusPx
        tick++
    }

    /** Where the ink would land under a hovering pointer (screen pixels), its radius and color. */
    var inkCursor: Offset? = null
        private set
    var inkCursorRadiusPx: Float = 0f
        private set
    var inkCursorColor: Int = 0
        private set

    fun setInkCursor(position: Offset?, radiusPx: Float = 0f, color: Int = 0) {
        if (inkCursor == position && inkCursorRadiusPx == radiusPx && inkCursorColor == color) return
        inkCursor = position
        inkCursorRadiusPx = radiusPx
        inkCursorColor = color
        tick++
    }

    fun invalidate() {
        tick++
    }
}

/**
 * Routes the pointer events of the canvas (stylus, touch, mouse, wheel, trackpad) to inking,
 * erasing, lasso selection, selection transformations, text tool, selection and marking of the
 * text of the PDFs, page reordering and camera gestures, like the `CanvasTouchDispatcher` of the
 * original Android implementation.
 */
internal class CanvasGestureHandler(
    private val controller: DrawViewController,
    private val engine: InkStrokeEngine,
    private val live: CanvasLiveState
) {
    private enum class Mode {
        NONE, INK, LASSO, ERASE, PAN, PINCH,
        SELECTION_MOVE, SELECTION_SCALE, SELECTION_ROTATE, TEXT_RESIZE_LEFT, TEXT_RESIZE_RIGHT,
        PAGE_REORDER, TEXT_TAP, PLACE_TAP, EDITOR_TAP,

        /** Moving the text being written by its frame, or one of its sides. */
        EDITOR_MOVE, EDITOR_RESIZE_LEFT, EDITOR_RESIZE_RIGHT,

        /** Drawing the box of a new text with a pen or a mouse (the text tool). */
        TEXT_BOX,

        /** Marking the text of a PDF with the text markup tool. */
        MARKUP,

        /** Dragging an end of the selected text of a PDF. */
        TEXT_HANDLE,
        IGNORED
    }

    private var mode = Mode.NONE
    private var activePointer: PointerId? = null
    private var activeType: PointerType = PointerType.Unknown
    private var downPosition = Offset.Zero
    private var downTime = 0L
    private var lastPosition = Offset.Zero
    private var movedBeyondSlop = false
    private var longPressCandidate = false
    private var inkPageIndex = -1
    private var inkBrush: BrushConfig? = null

    /** The strokes drawn so far (the one in progress: the last one), see [reportActivity]. */
    private var strokeCount = 0L
    private var inkInputType = InputToolType.UNKNOWN
    private var lastTapTime = 0L
    private var lastTapPosition = Offset.Zero
    private var hasSeenStylus = false

    /** The text selected before the gesture is dropped when it ends (unless it moves the canvas). */
    private var clearTextSelectionOnUp = false

    /** ⌥ / Alt held: the gesture moves and places freely, without snapping. */
    private var freeGesture = false

    /** Where the box of the text being written was when the gesture started moving it, and the move so far (page mm). */
    private var editorMoveOrigin = Offset.Zero
    private var editorMoveOffset = Offset.Zero

    // Pinch tracking
    private var pinchCentroid = Offset.Zero
    private var pinchDistance = 0f

    private val velocityTracker = VelocityTracker()

    // Two fingers moving on a trackpad: content offset since the start of the gesture
    private val trackpadVelocityTracker = VelocityTracker()
    private var trackpadPanPosition = Offset.Zero
    private var lastTrackpadSampleTime = 0L
    private var isTrackpadPanning = false

    // Phases of the two-finger scroll of a macOS trackpad (onTrackpadScrollPhase)
    private var trackpadFingersDown = false
    private var trackpadMomentum = false

    private val isDrawingTool: Boolean
        get() = controller.toolManager.isInkTool || controller.toolManager.isLassoTool || controller.toolManager.isEraserTool

    suspend fun PointerInputScope.run() {
        val touchSlop = viewConfiguration.touchSlop
        val longPressTimeout = viewConfiguration.longPressTimeoutMillis
        val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis

        awaitPointerEventScope {
            while (true) {
                val event = if (longPressCandidate) {
                    val remaining = longPressTimeout - (currentEventTime() - downTime)
                    if (remaining <= 0L) null else withTimeoutOrNull(remaining) { awaitPointerEvent() }
                } else {
                    awaitPointerEvent()
                }

                if (event == null) {
                    onLongPress()
                    continue
                }
                handle(event, touchSlop, doubleTapTimeout)
            }
        }
    }

    private var lastEventTime = 0L

    private fun currentEventTime(): Long = lastEventTime

    private fun AwaitPointerEventScope.handle(event: PointerEvent, touchSlop: Float, doubleTapTimeout: Long) {
        val changes = event.changes
        changes.firstOrNull()?.let { lastEventTime = it.uptimeMillis }
        freeGesture = event.keyboardModifiers.isAltPressed

        when (event.type) {
            PointerEventType.Scroll -> {
                onScroll(event)
                return
            }
            PointerEventType.PanStart, PointerEventType.PanMove, PointerEventType.PanEnd -> {
                onTrackpadPan(event)
                return
            }
            PointerEventType.ScaleStart, PointerEventType.ScaleChange, PointerEventType.ScaleEnd -> {
                onTrackpadScale(event)
                return
            }
            PointerEventType.Exit -> {
                live.overEditedTextFrame = false
                live.setEraserCursor(null, 0f)
                live.setInkCursor(null)
                controller.placementManager.pointer = null
                return
            }
            else -> {}
        }

        val pressed = changes.filter { it.pressed }
        val anyDown = changes.any { it.changedToDownIgnoreConsumed() }
        val anyUp = changes.any { it.changedToUpIgnoreConsumed() }

        // Hover (desktop mouse, stylus hover): where the eraser or the ink would land, preview of a
        // pending placement
        if (pressed.isEmpty() && !anyUp) {
            val hover = changes.firstOrNull()
            live.overEditedTextFrame = hover != null && controller.textEditorManager.isEditing &&
                SelectionFrame.ofEditedText(controller)?.contains(hover.position, FrameStyle.hitRadiusPx(hover.type, controller.density)) == true
            if (hover != null && controller.placementManager.isActive) {
                controller.placementManager.pointer = hover.position
            } else if (hover != null && controller.isDrawingMode && mode == Mode.NONE) {
                val tools = controller.toolManager
                if (tools.isEraserTool) live.setEraserCursor(hover.position, eraserRadiusPx(hover.position)) else live.setEraserCursor(null, 0f)
                val ink = tools.activePreset()?.takeIf { tools.isInkTool }
                if (ink != null) live.setInkCursor(hover.position, inkRadiusPx(hover.position, ink.sizeMm), ink.color) else live.setInkCursor(null)
            }
            return
        }

        if (anyDown && mode == Mode.NONE && pressed.size == 1) {
            // The stroke shows where the ink lands
            live.setInkCursor(null)
            onFirstDown(pressed[0], event)
            return
        }

        if (anyDown && pressed.size >= 2) {
            onAdditionalPointer(pressed)
            changes.forEach { it.consume() }
            return
        }

        if (pressed.isEmpty() && anyUp) {
            val up = changes.firstOrNull { it.changedToUpIgnoreConsumed() } ?: changes.first()
            onAllUp(up, touchSlop, doubleTapTimeout)
            changes.forEach { it.consume() }
            return
        }

        // Move
        if (mode == Mode.PINCH) {
            onPinchMove(pressed)
            changes.forEach { it.consume() }
            return
        }
        val change = pressed.firstOrNull { it.id == activePointer } ?: return
        onMove(change, touchSlop)
        change.consume()
    }

    // ==========================================================
    // Down
    // ==========================================================

    private fun onFirstDown(change: PointerInputChange, event: PointerEvent) {
        val position = change.position
        activePointer = change.id
        activeType = change.type
        downPosition = position
        lastPosition = position
        downTime = change.uptimeMillis
        movedBeyondSlop = false
        longPressCandidate = false
        velocityTracker.resetTracking()
        velocityTracker.addPosition(change.uptimeMillis, position)
        change.consume()

        controller.contextMenuPosition = null
        controller.cameraPhysics.stopAllAnimations()

        val isStylus = change.type == PointerType.Stylus || change.type == PointerType.Eraser
        if (isStylus && !hasSeenStylus) {
            hasSeenStylus = true
            controller.onFirstStylusDetected()
        }

        // Page reordering takes over every gesture
        if (controller.pageManager.isReorderingPages) {
            mode = if (controller.pageManager.startDraggingPage(position)) Mode.PAGE_REORDER else Mode.IGNORED
            return
        }

        // Selected text of a PDF: its handles extend it
        val textMarkup = controller.textMarkupManager
        clearTextSelectionOnUp = false
        if (textMarkup.hasSelection) {
            val handle = textMarkup.handleAt(position, FrameStyle.hitRadiusPx(change.type, controller.density))
            if (handle != null) {
                textMarkup.beginHandleDrag(handle, position)
                mode = Mode.TEXT_HANDLE
                return
            }
            clearTextSelectionOnUp = true
        }

        val isMouse = change.type == PointerType.Mouse

        // Text editor open: its frame moves it, its sides resize it; a tap outside the box saves
        // it, a drag or a pinch moves the canvas
        if (controller.textEditorManager.isEditing && controller.isDrawingMode) {
            if (isMouse && event.buttons.isTertiaryPressed) {
                startPan()
                return
            }
            val frame = SelectionFrame.ofEditedText(controller)
            val radius = FrameStyle.hitRadiusPx(change.type, controller.density)
            mode = when {
                frame == null -> Mode.EDITOR_TAP
                else -> when (frame.handleAt(position, radius)) {
                    SelectionFrame.Handle.LEFT -> Mode.EDITOR_RESIZE_LEFT
                    SelectionFrame.Handle.RIGHT -> Mode.EDITOR_RESIZE_RIGHT
                    else -> if (frame.contains(position, radius)) Mode.EDITOR_MOVE else Mode.EDITOR_TAP
                }
            }
            val editor = controller.textEditorManager
            editorMoveOrigin = Offset(editor.boxX, editor.boxY)
            editorMoveOffset = Offset.Zero
            return
        }

        if (isMouse && event.buttons.isSecondaryPressed) {
            mode = Mode.IGNORED
            showContextMenu(position)
            return
        }
        if (isMouse && event.buttons.isTertiaryPressed) {
            startPan()
            return
        }

        // Pending placement: a tap places the element, a drag moves the canvas (whatever the tool)
        if (controller.placementManager.isActive) {
            if (controller.isStylusOnlyMode && change.type == PointerType.Touch) {
                startPan()
            } else {
                mode = Mode.PLACE_TAP
                controller.placementManager.pointer = position
            }
            return
        }

        if (!controller.isDrawingMode) {
            startPan()
            // A long press selects the text of the PDFs
            longPressCandidate = true
            return
        }

        // Selection handles and body
        val selection = controller.selectionManager.currentSelection
        if (selection != null) {
            val frame = SelectionFrame.compute(controller, selection)
            if (frame != null) {
                val hitMode = when (frame.handleAt(position, FrameStyle.hitRadiusPx(change.type, controller.density))) {
                    SelectionFrame.Handle.ROTATION -> Mode.SELECTION_ROTATE
                    SelectionFrame.Handle.CORNER -> Mode.SELECTION_SCALE
                    SelectionFrame.Handle.LEFT -> Mode.TEXT_RESIZE_LEFT
                    SelectionFrame.Handle.RIGHT -> Mode.TEXT_RESIZE_RIGHT
                    null -> if (frame.contains(position)) Mode.SELECTION_MOVE else null
                }
                if (hitMode != null) {
                    mode = hitMode
                    controller.selectionManager.beginTransformation()
                    return
                }
            }
            // Touch outside the selection: drop it and continue with the active tool
            controller.selectionManager.clearSelection()
        }

        val stylusOnly = controller.isStylusOnlyMode
        val isFinger = change.type == PointerType.Touch
        val tool = controller.toolManager.selectedTool

        if (tool == Tool.PAN || (stylusOnly && isFinger)) {
            startPan()
            // With the hand tool, a long press only selects the text of the PDFs
            longPressCandidate = isFinger || tool == Tool.PAN
            return
        }

        if (tool == Tool.TEXT) {
            mode = Mode.TEXT_TAP
            longPressCandidate = !isStylus
            return
        }

        // The eraser end of a stylus, the eraser tool, or a stylus whose button is held
        if (change.type == PointerType.Eraser || tool == Tool.ERASER || (isStylus && event.isStylusButtonPressed())) {
            mode = Mode.ERASE
            controller.eraserManager.eraseAlongSegment(position, position)
            live.setEraserCursor(position, eraserRadiusPx(position))
            longPressCandidate = !isStylus
            return
        }

        if (tool == Tool.TEXT_MARKUP) {
            longPressCandidate = !isStylus
            if (textMarkup.beginMarking(position, textTolerancePx(change.type))) {
                mode = Mode.MARKUP
            } else {
                // Away from the text of the PDFs: the gesture pans the canvas
                startPan()
            }
            return
        }

        if (isDrawingTool) {
            val pageIndex = controller.findPageIndexAtScreen(position)
            val brush = pageIndex?.let { controller.brushForActiveTool(it) }
            if (pageIndex == null || brush == null) {
                // Outside of any page: the gesture pans the canvas
                startPan()
                longPressCandidate = !isStylus
                return
            }
            inkPageIndex = pageIndex
            inkBrush = brush
            mode = if (tool == Tool.LAZO) Mode.LASSO else Mode.INK
            engine.startStroke(brush)
            if (mode == Mode.INK) strokeCount++
            addInkInput(change, position, change.uptimeMillis, change.pressure)
            live.setLiveMesh(engine.currentMesh(), pageIndex)
            reportActivity(position, inking = mode == Mode.INK)
            longPressCandidate = !isStylus
            return
        }

        startPan()
    }

    private fun startPan() {
        mode = Mode.PAN
        controller.cameraPhysics.onDragStart()
    }

    private fun onAdditionalPointer(pressed: List<PointerInputChange>) {
        // A finger landing while the stylus is drawing is a palm: ignore it
        val stylusDrawing = (mode == Mode.INK || mode == Mode.LASSO || mode == Mode.ERASE || mode == Mode.MARKUP) &&
                (activeType == PointerType.Stylus || activeType == PointerType.Eraser)
        if (stylusDrawing) return

        when (mode) {
            Mode.INK, Mode.LASSO -> {
                engine.cancelStroke()
                live.setLiveMesh(null, -1)
                // Given up for the gesture: gone at once on the other devices too
                if (mode == Mode.INK) {
                    val position = pressed.firstOrNull { it.id == activePointer }?.position ?: downPosition
                    reportActivity(position, inking = false, strokeCancelled = true)
                }
            }
            Mode.ERASE -> {
                controller.eraserManager.commitGesture()
                live.setEraserCursor(null, 0f)
            }
            Mode.MARKUP -> controller.textMarkupManager.cancelMarking()
            Mode.SELECTION_MOVE, Mode.SELECTION_SCALE, Mode.SELECTION_ROTATE,
            Mode.TEXT_RESIZE_LEFT, Mode.TEXT_RESIZE_RIGHT -> {
                controller.selectionManager.applyTransformation()
            }
            Mode.TEXT_BOX -> live.textBoxDraft = null
            Mode.PAGE_REORDER -> return
            else -> {}
        }
        longPressCandidate = false
        mode = Mode.PINCH
        controller.cameraPhysics.onDragStart()
        val p1 = pressed[0].position
        val p2 = pressed[1].position
        pinchCentroid = (p1 + p2) / 2f
        pinchDistance = (p1 - p2).getDistance().coerceAtLeast(1f)
    }

    // ==========================================================
    // Move
    // ==========================================================

    private fun onMove(change: PointerInputChange, touchSlop: Float) {
        val position = change.position
        velocityTracker.addPosition(change.uptimeMillis, position)
        if (!movedBeyondSlop && (position - downPosition).getDistance() > touchSlop) {
            movedBeyondSlop = true
            longPressCandidate = false
        }

        when (mode) {
            Mode.INK, Mode.LASSO -> {
                for (h in change.historical) {
                    addInkInput(change, h.position, h.uptimeMillis, change.pressure)
                }
                addInkInput(change, position, change.uptimeMillis, change.pressure)
                live.setLiveMesh(engine.currentMesh(), inkPageIndex)
            }

            Mode.ERASE -> {
                var previous = lastPosition
                for (h in change.historical) {
                    controller.eraserManager.eraseAlongSegment(previous, h.position)
                    previous = h.position
                }
                controller.eraserManager.eraseAlongSegment(previous, position)
                live.setEraserCursor(position, eraserRadiusPx(position))
            }

            Mode.PAN -> {
                val delta = position - lastPosition
                controller.cameraPhysics.onDrag(delta.x, delta.y, 1f, position.x, position.y)
                controller.requestRedraw()
            }

            Mode.MARKUP -> {
                if (movedBeyondSlop) {
                    controller.textMarkupManager.updateMarking(position)
                    updateAutoScroll(position)
                }
            }

            Mode.TEXT_HANDLE -> {
                controller.textMarkupManager.dragHandleTo(position)
                updateAutoScroll(position)
            }

            Mode.EDITOR_MOVE -> {
                val editor = controller.textEditorManager
                val pxPerMm = controller.pixelsPerMm(editor.activeTextPageIndex).coerceAtLeast(0.01f)
                val delta = position - lastPosition
                editorMoveOffset += Offset(delta.x / pxPerMm, delta.y / pxPerMm)
                editor.moveTo(editorMoveOrigin.x + editorMoveOffset.x, editorMoveOrigin.y + editorMoveOffset.y, freeGesture)
                updateAutoScroll(position)
            }

            Mode.EDITOR_RESIZE_LEFT, Mode.EDITOR_RESIZE_RIGHT -> {
                val editor = controller.textEditorManager
                val left = mode == Mode.EDITOR_RESIZE_LEFT
                val pxPerMm = controller.pixelsPerMm(editor.activeTextPageIndex).coerceAtLeast(0.01f)
                val mm = controller.screenToPageMm(editor.activeTextPageIndex, position)
                if (editor.rotation == 0f && mm != null) {
                    // The handle is on the frame, its padding away from the text
                    val padding = FrameStyle.PADDING_DP * controller.density / pxPerMm
                    editor.resizeTo(if (left) mm.x + padding else mm.x - padding, left, freeGesture)
                } else {
                    // Along the width of the box, turned with the text
                    val axis = SelectionFrame.ofEditedText(controller)?.horizontalAxis
                    if (axis != null) {
                        val delta = position - lastPosition
                        editor.resizeBy((delta.x * axis.x + delta.y * axis.y) / pxPerMm, leftEdge = left)
                    }
                }
            }

            Mode.TEXT_BOX -> live.textBoxDraft = boxBetween(downPosition, position)

            Mode.TEXT_TAP, Mode.PLACE_TAP, Mode.EDITOR_TAP -> {
                if (movedBeyondSlop && mode == Mode.TEXT_TAP && activeType != PointerType.Touch) {
                    // A pen or a mouse draws the box of the text; a finger moves the canvas
                    mode = Mode.TEXT_BOX
                    live.textBoxDraft = boxBetween(downPosition, position)
                } else if (movedBeyondSlop) {
                    mode = Mode.PAN
                    controller.cameraPhysics.onDragStart()
                    val delta = position - downPosition
                    controller.cameraPhysics.onDrag(delta.x, delta.y, 1f, position.x, position.y)
                }
            }

            Mode.SELECTION_MOVE -> {
                val selection = controller.selectionManager.currentSelection
                if (selection != null) {
                    val pxPerMm = controller.pixelsPerMm(selection.pageIndex).coerceAtLeast(0.01f)
                    val delta = position - lastPosition
                    controller.selectionManager.moveBy(delta.x / pxPerMm, delta.y / pxPerMm, freeGesture)
                }
                updateAutoScroll(position)
            }

            Mode.SELECTION_SCALE -> {
                val frame = controller.selectionManager.currentSelection?.let { SelectionFrame.compute(controller, it) }
                if (frame != null) {
                    val c = frame.center
                    val previous = (lastPosition - c).getDistance()
                    val current = (position - c).getDistance()
                    if (previous > 10f) controller.selectionManager.scaleBy(current / previous)
                }
            }

            Mode.SELECTION_ROTATE -> {
                val frame = controller.selectionManager.currentSelection?.let { SelectionFrame.compute(controller, it) }
                if (frame != null) {
                    val c = frame.center
                    val previous = atan2(lastPosition.y - c.y, lastPosition.x - c.x)
                    val current = atan2(position.y - c.y, position.x - c.x)
                    var delta = (current - previous) * 180f / kotlin.math.PI.toFloat()
                    if (delta > 180f) delta -= 360f
                    if (delta < -180f) delta += 360f
                    controller.selectionManager.rotateBy(delta)
                }
            }

            Mode.TEXT_RESIZE_LEFT, Mode.TEXT_RESIZE_RIGHT -> {
                val selection = controller.selectionManager.currentSelection
                if (selection != null) {
                    controller.screenToPageMm(selection.pageIndex, position)?.let {
                        controller.selectionManager.resizeText(it, mode == Mode.TEXT_RESIZE_RIGHT, freeGesture)
                    }
                }
            }

            Mode.PAGE_REORDER -> {
                controller.pageManager.updateDragPosition(position)
                updateAutoScroll(position)
            }

            else -> {}
        }
        if (mode != Mode.PAN && mode != Mode.PINCH && mode != Mode.IGNORED) reportActivity(position, inking = mode == Mode.INK)
        lastPosition = position
    }

    private fun onPinchMove(pressed: List<PointerInputChange>) {
        if (pressed.isEmpty()) return
        if (pressed.size == 1) {
            // One finger lifted: keep panning with the remaining one
            val p = pressed[0]
            val delta = p.position - p.previousPosition
            controller.cameraPhysics.onDrag(delta.x, delta.y, 1f, p.position.x, p.position.y)
            pinchCentroid = p.position
            controller.requestRedraw()
            return
        }
        val p1 = pressed[0].position
        val p2 = pressed[1].position
        val centroid = (p1 + p2) / 2f
        val distance = (p1 - p2).getDistance().coerceAtLeast(1f)
        val pressedCountChanged = pressed.any { it.changedToDownIgnoreConsumed() }
        if (!pressedCountChanged) {
            val delta = centroid - pinchCentroid
            controller.cameraPhysics.onDrag(delta.x, delta.y, distance / pinchDistance, centroid.x, centroid.y)
            controller.requestRedraw()
        }
        pinchCentroid = centroid
        pinchDistance = distance
    }

    // ==========================================================
    // Up
    // ==========================================================

    private fun onAllUp(up: PointerInputChange, touchSlop: Float, doubleTapTimeout: Long) {
        val position = up.position
        val wasTap = !movedBeyondSlop && (position - downPosition).getDistance() <= touchSlop
        longPressCandidate = false
        live.autoScrollPerFrame = 0f

        if (clearTextSelectionOnUp && mode != Mode.PINCH && !(mode == Mode.PAN && movedBeyondSlop)) {
            controller.textMarkupManager.clearSelection()
        }
        clearTextSelectionOnUp = false

        when (mode) {
            Mode.INK -> {
                if (activePointer == up.id) addInkInput(up, position, up.uptimeMillis, up.pressure)
                val mesh = engine.finishStroke()
                val brush = inkBrush
                live.setLiveMesh(null, -1)
                if (mesh != null && brush != null) controller.commitInkStroke(mesh, inkPageIndex, brush)
                reportActivity(position, inking = false)
            }

            Mode.LASSO -> {
                val points = engine.inputs.toList()
                engine.cancelStroke()
                live.setLiveMesh(null, -1)
                val polygon = FloatArray(points.size * 2)
                points.forEachIndexed { i, p ->
                    polygon[i * 2] = p.x
                    polygon[i * 2 + 1] = p.y
                }
                controller.selectionManager.selectWithLasso(inkPageIndex, polygon)
            }

            Mode.ERASE -> {
                controller.eraserManager.commitGesture()
                if (activeType != PointerType.Mouse) live.setEraserCursor(null, 0f)
            }

            Mode.PAN -> {
                val velocity = velocityTracker.calculateVelocity()
                controller.cameraPhysics.onRelease(velocity.x, velocity.y)
                controller.requestRedraw()
            }

            Mode.PINCH -> {
                controller.cameraPhysics.onRelease(0f, 0f)
                controller.requestRedraw()
            }

            Mode.MARKUP -> {
                val textMarkup = controller.textMarkupManager
                if (wasTap) {
                    // A tap of the tool selects the mark under it
                    textMarkup.cancelMarking()
                    textMarkup.selectMarkupAt(position, textTolerancePx(up.type))
                } else {
                    textMarkup.updateMarking(position)
                    textMarkup.finishMarking()
                }
            }

            Mode.SELECTION_MOVE, Mode.SELECTION_SCALE, Mode.SELECTION_ROTATE,
            Mode.TEXT_RESIZE_LEFT, Mode.TEXT_RESIZE_RIGHT -> {
                controller.selectionManager.applyTransformation()
            }

            Mode.PAGE_REORDER -> {
                controller.pageManager.finishPageReorderMode()
            }

            Mode.TEXT_TAP -> {
                if (wasTap) onTextToolTap(position)
            }

            Mode.EDITOR_MOVE, Mode.EDITOR_RESIZE_LEFT, Mode.EDITOR_RESIZE_RIGHT -> controller.snapManager.clearGuides()

            Mode.TEXT_BOX -> {
                live.textBoxDraft = null
                onTextBoxDrawn(boxBetween(downPosition, position))
            }

            Mode.PLACE_TAP -> {
                if (wasTap) controller.placementManager.placeAt(position)
            }

            Mode.EDITOR_TAP -> {
                // On another text: saves this one and opens that one; elsewhere: saves this one
                if (wasTap && !editTextAt(position)) controller.textEditorManager.finishEditing()
            }

            else -> {}
        }

        // Double tap on a text block: edit it (whatever the active tool)
        val editorMode = mode == Mode.EDITOR_TAP || mode == Mode.EDITOR_MOVE || mode == Mode.EDITOR_RESIZE_LEFT || mode == Mode.EDITOR_RESIZE_RIGHT
        if (wasTap && mode != Mode.IGNORED && mode != Mode.PAGE_REORDER && !editorMode) {
            val isDouble = up.uptimeMillis - lastTapTime <= doubleTapTimeout &&
                    (position - lastTapPosition).getDistance() <= touchSlop * 2f
            if (isDouble && mode != Mode.TEXT_TAP && mode != Mode.PLACE_TAP) {
                editTextAt(position)
            }
            lastTapTime = up.uptimeMillis
            lastTapPosition = position
        }

        mode = Mode.NONE
        activePointer = null
    }

    private fun onLongPress() {
        longPressCandidate = false
        if (movedBeyondSlop) return
        when (mode) {
            Mode.INK, Mode.LASSO -> {
                engine.cancelStroke()
                live.setLiveMesh(null, -1)
                if (mode == Mode.INK) reportActivity(downPosition, inking = false, strokeCancelled = true)
            }
            Mode.ERASE -> controller.eraserManager.commitGesture()
            Mode.PAN -> controller.cameraPhysics.onRelease(0f, 0f)
            Mode.MARKUP -> controller.textMarkupManager.cancelMarking()
            else -> {}
        }
        mode = Mode.IGNORED
        // A mark or the text of a PDF under the finger: select it
        clearTextSelectionOnUp = false
        if (controller.textMarkupManager.selectAt(downPosition, textTolerancePx(activeType))) {
            controller.cameraPhysics.stopAllAnimations()
            return
        }
        if (controller.isDrawingMode && !controller.toolManager.isPanTool) showContextMenu(downPosition)
    }

    private fun showContextMenu(position: Offset) {
        val pageIndex = controller.findPageIndexAtScreen(position) ?: return
        controller.pageManager.contextMenuTargetPageIndex = pageIndex
        val page = controller.document.pages.getOrNull(pageIndex)
        val mm = controller.screenToPageMm(pageIndex, position)
        if (page != null && mm != null) {
            val image = page.images.lastOrNull { it.containsPoint(mm.x, mm.y) }
            if (image != null) controller.selectionManager.selectImage(pageIndex, image)
        }
        controller.contextMenuPosition = position
        controller.cameraPhysics.stopAllAnimations()
    }

    // ==========================================================
    // Text tool
    // ==========================================================

    private fun onTextToolTap(position: Offset) {
        if (editTextAt(position)) return
        val pageIndex = controller.findPageIndexAtScreen(position) ?: return
        val mm = controller.screenToPageMm(pageIndex, position) ?: return
        controller.textEditorManager.startNewText(pageIndex, mm.x, mm.y, freeGesture)
    }

    /** Starts a new text in the box drawn on the screen [box] with the text tool. */
    private fun onTextBoxDrawn(box: Rect) {
        val pageIndex = controller.findPageIndexAtScreen(box.topLeft) ?: controller.findPageIndexAtScreen(box.center) ?: return
        val topLeft = controller.screenToPageMm(pageIndex, box.topLeft) ?: return
        val bottomRight = controller.screenToPageMm(pageIndex, box.bottomRight) ?: return
        controller.textEditorManager.startDrawnText(pageIndex, Rect(topLeft, bottomRight))
    }

    /**
     * Opens the editor on the text block under [position] (not the one being written), the caret
     * on the character there.
     * @return `true` if a text block was found.
     */
    private fun editTextAt(position: Offset): Boolean {
        val pageIndex = controller.findPageIndexAtScreen(position) ?: return false
        val page = controller.document.pages.getOrNull(pageIndex) ?: return false
        val mm = controller.screenToPageMm(pageIndex, position) ?: return false
        val edited = controller.textEditorManager.activeTextItem
        val text = page.texts.lastOrNull { it !== edited && it.containsPoint(mm.x, mm.y) } ?: return false
        controller.textEditorManager.startEditing(text, pageIndex, caretAt = text.toLocal(mm.x, mm.y).toCompose())
        return true
    }

    // ==========================================================
    // Wheel / trackpad
    // ==========================================================

    private fun onScroll(event: PointerEvent) {
        val change = event.changes.firstOrNull() ?: return
        val delta = change.scrollDelta
        val modifiers = event.keyboardModifiers
        if (modifiers.isCtrlPressed || modifiers.isMetaPressed) {
            // Also the pinch of the Windows precision touchpads, reported as Ctrl + wheel
            zoomBy(exp(-delta.y * 0.1f).coerceIn(0.5f, 2f), change.position)
        } else {
            val unit = scrollUnitPx(controller.density)
            // Shift + wheel scrolls horizontally. The desktop already reports it as a horizontal
            // delta, like the horizontal scroll of a macOS trackpad (which comes with Shift)
            val swapAxes = modifiers.isShiftPressed && delta.x == 0f
            val pan = Offset(
                if (swapAxes) -delta.y * unit else -delta.x * unit,
                if (swapAxes) 0f else -delta.y * unit
            )
            when {
                // The canvas keeps the speed of the fingers by itself (endTrackpadPan)
                trackpadMomentum -> {}
                // Two fingers on a macOS trackpad: like a touch pan, beyond the edges too
                trackpadFingersDown -> {
                    if (!isTrackpadPanning) startTrackpadPan()
                    trackpadPanBy(pan, change)
                }
                else -> {
                    controller.cameraPhysics.onDrag(pan.x, pan.y, 1f, change.position.x, change.position.y)
                    controller.cameraPhysics.restoreToBounds(animated = false)
                    controller.requestRedraw()
                }
            }
        }
        change.consume()
    }

    /** Zooms by [factor] around [focus] at once (wheel with Ctrl / Cmd). */
    private fun zoomBy(factor: Float, focus: Offset) {
        controller.cameraPhysics.onDrag(0f, 0f, factor, focus.x, focus.y)
        controller.cameraPhysics.restoreToBounds(animated = false)
        controller.requestRedraw()
    }

    /** Phases of the two-finger scroll of a macOS trackpad, reported apart from its scroll events. */
    fun onTrackpadScrollPhase(phase: TrackpadScrollPhase, timeMillis: Long) {
        when (phase) {
            TrackpadScrollPhase.FINGERS_DOWN -> {
                trackpadFingersDown = true
                trackpadMomentum = false
                // Like a finger on a touch screen, the fingers on the trackpad stop the canvas
                if (controller.cameraPhysics.isAnimating()) startTrackpadPan()
            }
            TrackpadScrollPhase.FINGERS_UP -> {
                trackpadFingersDown = false
                endTrackpadPan(timeMillis)
            }
            TrackpadScrollPhase.MOMENTUM_START -> trackpadMomentum = true
            TrackpadScrollPhase.MOMENTUM_END -> trackpadMomentum = false
            TrackpadScrollPhase.WHEEL -> {
                trackpadFingersDown = false
                trackpadMomentum = false
                endTrackpadPan(timeMillis)
            }
        }
    }

    /**
     * Two fingers moving on a trackpad (Android, iOS): the canvas follows them and keeps their
     * speed when they are lifted, like with a touch pan.
     */
    private fun onTrackpadPan(event: PointerEvent) {
        val change = event.changes.firstOrNull() ?: return
        if (event.type == PointerEventType.PanStart) startTrackpadPan()
        // The pan offset is a scroll distance: the content moves the other way
        var delta = -change.panOffset
        for (h in change.historical) delta -= h.panOffset
        trackpadPanBy(delta, change)
        if (event.type == PointerEventType.PanEnd) endTrackpadPan(change.uptimeMillis)
        change.consume()
    }

    private fun startTrackpadPan() {
        isTrackpadPanning = true
        controller.cameraPhysics.onDragStart()
        trackpadVelocityTracker.resetTracking()
        trackpadPanPosition = Offset.Zero
        lastTrackpadSampleTime = 0L
    }

    /** Moves the canvas with the fingers, beyond its edges too (rubber band). */
    private fun trackpadPanBy(delta: Offset, change: PointerInputChange) {
        if (delta == Offset.Zero) return
        controller.cameraPhysics.onDrag(delta.x, delta.y, 1f, change.position.x, change.position.y)
        trackpadPanPosition += delta
        trackpadVelocityTracker.addPosition(change.uptimeMillis, trackpadPanPosition)
        lastTrackpadSampleTime = change.uptimeMillis
        controller.requestRedraw()
    }

    /**
     * Fingers lifted at [timeMillis]: the canvas keeps their speed and bounces back into its
     * edges.
     */
    private fun endTrackpadPan(timeMillis: Long) {
        if (!isTrackpadPanning) return
        isTrackpadPanning = false
        // Where the fingers were lifted: no fling if they had stopped before
        if (timeMillis > lastTrackpadSampleTime) {
            trackpadVelocityTracker.addPosition(timeMillis, trackpadPanPosition)
        }
        val velocity = trackpadVelocityTracker.calculateVelocity()
        controller.cameraPhysics.onRelease(velocity.x, velocity.y)
        controller.requestRedraw()
    }

    /** Pinch on a trackpad reported as pointer events (Android, iOS). */
    private fun onTrackpadScale(event: PointerEvent) {
        val change = event.changes.firstOrNull() ?: return
        var factor = change.scaleFactor
        for (h in change.historical) factor *= h.scaleFactor
        val phase = when (event.type) {
            PointerEventType.ScaleStart -> TrackpadPinchPhase.START
            PointerEventType.ScaleEnd -> TrackpadPinchPhase.END
            else -> TrackpadPinchPhase.CHANGE
        }
        onTrackpadPinch(phase, factor, change.position)
        change.consume()
    }

    /**
     * Pinch on a trackpad: zoom around the pointer like with a touch pinch, beyond the limits
     * with a rubber band.
     */
    fun onTrackpadPinch(phase: TrackpadPinchPhase, factor: Float, focus: Offset) {
        if (phase == TrackpadPinchPhase.START) controller.cameraPhysics.onDragStart()
        if (factor > 0f && factor != 1f) controller.cameraPhysics.onDrag(0f, 0f, factor, focus.x, focus.y)
        if (phase == TrackpadPinchPhase.END) controller.cameraPhysics.onRelease(0f, 0f)
        controller.requestRedraw()
    }

    // ==========================================================
    // Helpers
    // ==========================================================

    /**
     * Tells the controller where the pointer is ([screen]) and, when [inking], the stroke being
     * drawn, or that it was given up ([strokeCancelled], see [DrawViewController.activity]).
     */
    private fun reportActivity(screen: Offset, inking: Boolean, strokeCancelled: Boolean = false) {
        val pageIndex = if (inking) inkPageIndex else controller.findPageIndexAtScreen(screen)
        val page = pageIndex?.let { controller.document.pages.getOrNull(it) }
        val mm = pageIndex?.let { controller.screenToPageMm(it, screen) }
        if (page == null || mm == null) {
            controller.reportActivity(if (strokeCancelled) EditorActivity(strokeCancelled = true) else null)
            return
        }
        val brush = inkBrush.takeIf { inking }
        controller.reportActivity(
            EditorActivity(
                pageId = page.id,
                x = mm.x,
                y = mm.y,
                stroke = if (brush != null) strokeCount else 0,
                ink = if (brush != null) engine.inputs.toList() else emptyList(),
                brush = brush,
                inputType = inkInputType,
                strokeCancelled = strokeCancelled
            )
        )
    }

    private fun addInkInput(change: PointerInputChange, screen: Offset, timeMs: Long, pressure: Float) {
        val mm = controller.screenToPageMm(inkPageIndex, screen) ?: return
        val toolType = when (change.type) {
            PointerType.Stylus, PointerType.Eraser -> InputToolType.STYLUS
            PointerType.Touch -> InputToolType.TOUCH
            PointerType.Mouse -> InputToolType.MOUSE
            else -> InputToolType.UNKNOWN
        }
        if (engine.inputs.isEmpty()) inkInputType = toolType
        engine.addInput(mm.x, mm.y, pressure, timeMs, toolType)
    }

    /** How far from the text of the PDFs a pointer of [type] may be to select or mark it, in pixels. */
    private fun textTolerancePx(type: PointerType): Float =
        (if (type == PointerType.Touch) 12f else 6f) * controller.density

    /** The radius on screen of a stroke [sizeMm] wide drawn at [position]. */
    private fun inkRadiusPx(position: Offset, sizeMm: Float): Float {
        val pageIndex = controller.findPageIndexAtScreen(position) ?: controller.firstVisiblePageIndex() ?: 0
        return sizeMm / 2f * controller.pixelsPerMm(pageIndex)
    }

    private fun eraserRadiusPx(position: Offset): Float {
        val pageIndex = controller.findPageIndexAtScreen(position) ?: controller.firstVisiblePageIndex() ?: 0
        val diameterMm = controller.toolManager.activePreset(Tool.ERASER)?.sizeMm ?: 8f
        return diameterMm / 2f * controller.pixelsPerMm(pageIndex)
    }

    private fun updateAutoScroll(position: Offset) {
        val edge = 80f
        val height = controller.viewportHeight
        live.autoScrollPerFrame = when {
            position.y < edge -> (edge - position.y) * 0.25f
            position.y > height - edge -> -(position.y - (height - edge)) * 0.25f
            else -> 0f
        }
    }

    /**
     * Applied once per frame while dragging near the edges of the viewport.
     */
    fun onAutoScrollFrame() {
        val delta = live.autoScrollPerFrame
        if (delta == 0f || abs(delta) < 0.01f) return
        controller.cameraPhysics.onDrag(0f, delta, 1f, controller.viewportWidth / 2f, controller.viewportHeight / 2f)
        controller.cameraPhysics.restoreToBounds(animated = false)
        when (mode) {
            Mode.SELECTION_MOVE -> {
                // Keep the selection under the finger while the camera moves
                val selection = controller.selectionManager.currentSelection
                if (selection != null) {
                    val pxPerMm = controller.pixelsPerMm(selection.pageIndex).coerceAtLeast(0.01f)
                    controller.selectionManager.moveBy(0f, -delta / pxPerMm, freeGesture)
                }
            }
            Mode.EDITOR_MOVE -> {
                // Keep the text being written under the pointer while the camera moves
                val editor = controller.textEditorManager
                val pxPerMm = controller.pixelsPerMm(editor.activeTextPageIndex).coerceAtLeast(0.01f)
                editorMoveOffset += Offset(0f, -delta / pxPerMm)
                editor.moveTo(editorMoveOrigin.x + editorMoveOffset.x, editorMoveOrigin.y + editorMoveOffset.y, freeGesture)
            }
            Mode.PAGE_REORDER -> controller.pageManager.onCameraMovedWhileDragging()
            // Keep extending the text under the pointer while the camera moves
            Mode.MARKUP -> controller.textMarkupManager.updateMarking(lastPosition)
            Mode.TEXT_HANDLE -> controller.textMarkupManager.dragHandleTo(lastPosition)
            else -> {}
        }
        controller.requestRedraw()
    }
}

/** The rectangle with the corners [a] and [b], wherever they are. */
private fun boxBetween(a: Offset, b: Offset) = Rect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
