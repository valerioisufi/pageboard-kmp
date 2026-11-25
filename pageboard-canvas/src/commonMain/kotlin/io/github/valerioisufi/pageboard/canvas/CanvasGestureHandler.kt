package io.github.valerioisufi.pageboard.canvas

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
import io.github.valerioisufi.pageboard.canvas.controller.PageboardController
import io.github.valerioisufi.pageboard.canvas.controller.EditorActivity
import io.github.valerioisufi.pageboard.canvas.controller.GeometryGuidesManager
import io.github.valerioisufi.pageboard.canvas.controller.ShapeHandle
import io.github.valerioisufi.pageboard.canvas.controller.ShapeSnapper
import io.github.valerioisufi.pageboard.canvas.geometry.toCompose
import io.github.valerioisufi.pageboard.canvas.tool.Tool
import io.github.valerioisufi.pageboard.ink.InkStrokeEngine
import io.github.valerioisufi.pageboard.model.BrushConfig
import io.github.valerioisufi.pageboard.model.CustomElement
import io.github.valerioisufi.pageboard.model.ImageElement
import io.github.valerioisufi.pageboard.model.TextElement
import io.github.valerioisufi.pageboard.model.InputToolType
import io.github.valerioisufi.pageboard.model.StrokePoint
import io.github.valerioisufi.pageboard.model.geometry.Matrix2D
import io.github.valerioisufi.pageboard.model.geometry.Point
import io.github.valerioisufi.pageboard.model.shape.ShapeConverter
import io.github.valerioisufi.pageboard.model.shape.ShapeElementType
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp

/**
 * Live (not yet committed) state of the canvas other than the stroke in progress (see
 * [LiveInkLayer]), drawn on top of the tiles.
 */
internal class CanvasLiveState {
    /** Incremented whenever the cursors change. */
    var tick by mutableIntStateOf(0)
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
    private val controller: PageboardController,
    private val engine: InkStrokeEngine,
    private val live: CanvasLiveState,
    private val ink: LiveInkLayer,
    private val predictor: InkPredictor
) {
    private enum class Mode {
        NONE, INK, LASSO, ERASE, PAN, PINCH,
        SELECTION_MOVE, SELECTION_SCALE, SELECTION_ROTATE, TEXT_RESIZE_LEFT, TEXT_RESIZE_RIGHT,
        PAGE_REORDER, TEXT_TAP, PLACE_TAP, EDITOR_TAP,

        /** Pressing a text waiting to be placed: a tap confirms the placement, a drag moves it. */
        PLACE_ITEM,

        /** Moving the text being written by its frame, or one of its sides. */
        EDITOR_MOVE, EDITOR_RESIZE_LEFT, EDITOR_RESIZE_RIGHT,

        /** Drawing the box of a new text with a pen or a mouse (the text tool). */
        TEXT_BOX,

        /** Marking the text of a PDF with the text markup tool. */
        MARKUP,

        /** Dragging an end of the selected text of a PDF. */
        TEXT_HANDLE,

        /** Drawing a shape with the shapes tool (see `ShapeInserter`). */
        SHAPE,

        /** Dragging a handle of a shape alone in the selection (see `ShapeEditing`). */
        SHAPE_HANDLE,

        /** Dragging or rotating the on-canvas ruler or protractor guide. */
        GUIDE_DRAG,
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

    /** The text waiting to be placed that is pressed, and the point of it pressed (millimeters from its corner). */
    private var placementItem = -1
    private var placementGrab = Offset.Zero
    private var inkPageIndex = -1
    private var inkBrush: BrushConfig? = null

    /** The strokes drawn so far (the one in progress: the last one), see [reportActivity]. */
    private var strokeCount = 0L

    /** Where and when the pen last stopped in the stroke being drawn, and whether that stop was tried as a shape. */
    private var holdPosition = Offset.Zero
    private var holdTime = 0L
    private var holdTried = false

    /** The stroke being drawn became its shape: the pen moving on leaves it as it is. */
    private var strokeSnapped = false
    private var inkInputType = InputToolType.UNKNOWN
    private var lastTapTime = 0L
    private var lastTapPosition = Offset.Zero
    private var hasSeenStylus = false

    /** The text selected before the gesture is dropped when it ends (unless it moves the canvas). */
    private var clearTextSelectionOnUp = false

    /** ⌥ / Alt held: the gesture moves and places freely, without snapping. */
    private var freeGesture = false

    /** The keys held for a shape being drawn: Shift forces its constraints, Ctrl / ⌘ makes it free. */
    private var shiftHeld = false
    private var primaryHeld = false

    /** A second finger held while a finger draws a shape: it forces its constraints, as Shift. */
    private var shapeForcedByFinger = false

    /** The handle dragged when entering [Mode.SHAPE_HANDLE], and recent vertex tap state for double-tap delete. */
    private var activeShapeHandle: ShapeHandle? = null
    private var lastVertexTapTime = 0L
    private var lastVertexTapIndex = -1
    private var activeGuideHit: GeometryGuidesManager.GuideHit? = null
    private var lastLongPressPageId: String? = null
    private var lastLongPressScreenPos: Offset? = null
    private var lastLongPressElementId: String? = null

    /** The gesture dropped a selection: a tap of the shapes tool only drops it. */
    private var droppedSelection = false

    /** The corner of the text being written when its drag started, and the drag so far, in the world (its page may change). */
    private var editorMoveOrigin = Offset.Zero
    private var editorMoveOffset = Offset.Zero

    // Pinch tracking
    private var pinchCentroid = Offset.Zero
    private var pinchDistance = 0f
    private var initialPinchDistance = 0f
    private var isPinchZooming = false
    private val pinchVelocityTracker = VelocityTracker()
    private var pinchPanPosition = Offset.Zero
    private var lastPinchSampleTime = 0L

    private val velocityTracker = VelocityTracker()

    // Two fingers moving on a trackpad: content offset since the start of the gesture
    private val trackpadVelocityTracker = VelocityTracker()
    private var trackpadPanPosition = Offset.Zero
    private var lastTrackpadSampleTime = 0L
    private var isTrackpadPanning = false

    // Phases of the two-finger scroll of a macOS trackpad (onTrackpadScrollPhase)
    private var trackpadFingersDown = false
    private var trackpadMomentum = false

    // The axis of a trackpad gesture, and the one of a wheel without phases (ended by a pause)
    private val trackpadAxis = ScrollAxisLock { controller.viewport.density }
    private val wheelAxis = ScrollAxisLock { controller.viewport.density }
    private var lastWheelTime = 0L

    /** Whether the pen held still now would turn the stroke being drawn into its shape. */
    private val holdCandidate: Boolean
        get() = mode == Mode.INK && movedBeyondSlop && !strokeSnapped && !holdTried && controller.shapeSnapper.isEnabled

    private val isDrawingTool: Boolean
        get() = controller.toolManager.isInkTool || controller.toolManager.isLassoTool || controller.toolManager.isEraserTool

    suspend fun PointerInputScope.run() {
        val touchSlop = viewConfiguration.touchSlop
        val longPressTimeout = viewConfiguration.longPressTimeoutMillis
        val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis

        awaitPointerEventScope {
            while (true) {
                val remaining = when {
                    longPressCandidate -> longPressTimeout - (currentEventTime() - downTime)
                    holdCandidate -> ShapeSnapper.HOLD_MS - (currentEventTime() - holdTime)
                    else -> null
                }
                val event = when {
                    remaining == null -> awaitPointerEvent()
                    remaining <= 0L -> null
                    else -> withTimeoutOrNull(remaining) { awaitPointerEvent() }
                }

                if (event == null) {
                    if (longPressCandidate) onLongPress() else onHold()
                    continue
                }
                handle(event, touchSlop, doubleTapTimeout)
            }
        }
    }

    private var lastEventTime = 0L

    /** When the activity was last reported to the others (see [reportActivity]). */
    private var lastActivityTime = Long.MIN_VALUE / 2

    private fun currentEventTime(): Long = lastEventTime

    /**
     * The gesture of the [pressed] pointers, started over an element above the canvas, becomes a pan
     * or a pinch of the canvas (see [canvasGesturesOverField]).
     */
    fun takeOver(pressed: List<PointerInputChange>) {
        val first = pressed[0]
        activePointer = first.id
        activeType = first.type
        downPosition = first.position
        lastPosition = first.position
        movedBeyondSlop = true
        longPressCandidate = false
        velocityTracker.resetTracking()
        velocityTracker.addPosition(first.uptimeMillis, first.position)
        startPan()
        if (pressed.size >= 2) onAdditionalPointer(pressed)
        pressed.forEach { it.consume() }
    }

    fun AwaitPointerEventScope.handle(event: PointerEvent, touchSlop: Float, doubleTapTimeout: Long) {
        val changes = event.changes
        changes.firstOrNull()?.let { lastEventTime = it.uptimeMillis }
        freeGesture = event.keyboardModifiers.isAltPressed
        shiftHeld = event.keyboardModifiers.isShiftPressed
        primaryHeld = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed

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
                SelectionFrame.ofEditedText(controller)?.contains(hover.position, FrameStyle.hitRadiusPx(hover.type, controller.viewport.density)) == true
            if (hover != null && controller.placementManager.isActive) {
                controller.placementManager.pointer = hover.position
            } else if (hover != null && controller.isDrawingMode && mode == Mode.NONE) {
                val tools = controller.toolManager
                if (tools.selectedTool == Tool.SHAPES && controller.shapeInserter.isDrawingVertices) {
                    controller.viewport.screenToPageMm(inkPageIndex, hover.position)?.let { mm ->
                        controller.shapeInserter.previewVertex(Point(mm.x, mm.y), free = primaryHeld)
                    }
                }
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

        val shaping = mode == Mode.SHAPE || mode == Mode.SHAPE_HANDLE
        if (shaping && activeType == PointerType.Touch) shapeForcedByFinger = pressed.size >= 2
        if (anyDown && pressed.size >= 2 && shaping && activeType != PointerType.Mouse) {
            // A finger held while a finger draws forces the shape; a palm while a pen draws is ignored
            changes.filter { it.id != activePointer }.forEach { it.consume() }
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
            onPinchMove(pressed, touchSlop)
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

        controller.dismissContextMenu()
        controller.viewport.camera.stopAllAnimations()

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
            val handle = textMarkup.handleAt(position, FrameStyle.hitRadiusPx(change.type, controller.viewport.density))
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
            val radius = FrameStyle.hitRadiusPx(change.type, controller.viewport.density)
            mode = when {
                frame == null -> Mode.EDITOR_TAP
                else -> when (frame.handleAt(position, radius)) {
                    SelectionFrame.Handle.LEFT -> Mode.EDITOR_RESIZE_LEFT
                    SelectionFrame.Handle.RIGHT -> Mode.EDITOR_RESIZE_RIGHT
                    else -> if (frame.contains(position, radius)) Mode.EDITOR_MOVE else Mode.EDITOR_TAP
                }
            }
            val editor = controller.textEditorManager
            editorMoveOrigin = controller.viewport.pageMmToWorld(editor.activeTextPageIndex)
                ?.mapPoint(editor.boxX, editor.boxY)?.let { Offset(it.x, it.y) } ?: Offset.Zero
            editorMoveOffset = Offset.Zero
            return
        }

        if (isMouse && event.buttons.isSecondaryPressed) {
            mode = Mode.IGNORED
            showContextMenu(position, fromLongPress = false)
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
                val placement = controller.placementManager
                val item = placement.itemAt(position)
                val grab = item?.let { placement.grabOf(it, position) }
                if (item != null && grab != null) {
                    mode = Mode.PLACE_ITEM
                    placementItem = item
                    placementGrab = grab
                } else {
                    mode = Mode.PLACE_TAP
                }
                placement.pointer = position
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
        droppedSelection = false
        val selection = controller.selectionManager.currentSelection
        if (selection != null) {
            val frame = SelectionFrame.compute(controller, selection)
            if (frame != null) {
                val radius = FrameStyle.hitRadiusPx(change.type, controller.viewport.density)
                val handle = frame.handleAt(position, radius)
                // A shape alone: its ends, its corners and its sides edit it as a shape
                val shapeHandle = if (controller.shapeEditing.single() == null) null else when (handle) {
                    SelectionFrame.Handle.START -> ShapeHandle.End(start = true)
                    SelectionFrame.Handle.END -> ShapeHandle.End(start = false)
                    SelectionFrame.Handle.VERTEX -> frame.vertexAt(position, radius)?.let { ShapeHandle.Vertex(it) }
                    SelectionFrame.Handle.ADD_VERTEX -> frame.edgeMidpointAt(position, radius)?.let { ShapeHandle.AddVertex(it) }
                    SelectionFrame.Handle.CORNER -> frame.cornerAt(position, radius)?.let { ShapeHandle.Corner(it) }
                    SelectionFrame.Handle.TOP -> ShapeHandle.Side(0)
                    SelectionFrame.Handle.RIGHT -> ShapeHandle.Side(1)
                    SelectionFrame.Handle.BOTTOM -> ShapeHandle.Side(2)
                    SelectionFrame.Handle.LEFT -> ShapeHandle.Side(3)
                    else -> null
                }
                if (shapeHandle != null) {
                    mode = Mode.SHAPE_HANDLE
                    activeShapeHandle = shapeHandle
                    inkPageIndex = selection.pageIndex
                    shapeForcedByFinger = false
                    controller.shapeEditing.beginHandle(shapeHandle)
                    return
                }
                val hitMode = when (handle) {
                    SelectionFrame.Handle.ROTATION -> Mode.SELECTION_ROTATE
                    SelectionFrame.Handle.CORNER -> Mode.SELECTION_SCALE
                    SelectionFrame.Handle.LEFT -> Mode.TEXT_RESIZE_LEFT
                    SelectionFrame.Handle.RIGHT -> Mode.TEXT_RESIZE_RIGHT
                    SelectionFrame.Handle.TOP, SelectionFrame.Handle.BOTTOM, SelectionFrame.Handle.START, SelectionFrame.Handle.END,
                    SelectionFrame.Handle.VERTEX, SelectionFrame.Handle.ADD_VERTEX,
                    null -> if (frame.contains(position)) Mode.SELECTION_MOVE else null
                }
                if (hitMode != null) {
                    mode = hitMode
                    controller.selectionManager.beginTransformation()
                    // A long press on the selection opens its menu
                    longPressCandidate = hitMode == Mode.SELECTION_MOVE && change.type != PointerType.Mouse
                    return
                }
            }
            // Touch outside the selection: drop it and continue with the active tool
            controller.selectionManager.clearSelection()
            droppedSelection = true
        }

        val stylusOnly = controller.isStylusOnlyMode
        val isFinger = change.type == PointerType.Touch
        val tool = controller.toolManager.selectedTool

        // Interactive ruler & protractor: ink near an edge locks & traces it; touching body/knobs moves or rotates the guide
        val density = controller.viewport.density
        val inkNearGuide = controller.toolManager.isInkTool && !(stylusOnly && isFinger) &&
            controller.geometryGuides.beginInkStroke(position, density)
        if (!inkNearGuide) {
            val guideHit = controller.geometryGuides.hitTest(position, density)
            if (guideHit != null) {
                activeGuideHit = guideHit
                mode = Mode.GUIDE_DRAG
                return
            }
        }

        if (tool == Tool.PAN || (stylusOnly && isFinger)) {
            startPan()
            // With the hand tool, a long press only selects the text of the PDFs
            longPressCandidate = isFinger || tool == Tool.PAN
            return
        }

        if (tool == Tool.SHAPES) {
            val pageIndex = controller.viewport.findPageIndexAtScreen(position)
            val at = pageIndex?.let { controller.viewport.screenToPageMm(it, position) }
            if (pageIndex == null || at == null || controller.session.isReadOnly) {
                // Outside of any page: the gesture pans the canvas
                startPan()
                longPressCandidate = !isStylus
                return
            }
            mode = Mode.SHAPE
            inkPageIndex = pageIndex
            shapeForcedByFinger = false
            if (!controller.shapeInserter.isDrawingVertices) {
                controller.shapeInserter.begin(pageIndex, Point(at.x, at.y), free = primaryHeld)
            }
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
            val pageIndex = controller.viewport.findPageIndexAtScreen(position)
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
            strokeSnapped = false
            controller.shapeSnapper.release()
            holdTried = false
            holdPosition = position
            holdTime = change.uptimeMillis
            engine.startStroke(brush)
            if (mode == Mode.INK) {
                strokeCount++
                controller.inkLatencyMeter.strokeStarted()
            }
            addInkInput(change, position, change.uptimeMillis, change.pressure)
            predictor.start()
            predictor.record(position, change.uptimeMillis)
            ink.strokeChanged(engine, brush.color, pageIndex)
            reportActivity(position, inking = mode == Mode.INK)
            longPressCandidate = !isStylus
            return
        }

        startPan()
    }

    private fun startPan() {
        mode = Mode.PAN
        controller.viewport.camera.onDragStart()
    }

    private fun onAdditionalPointer(pressed: List<PointerInputChange>) {
        // A finger landing while the stylus is drawing is a palm: ignore it
        val stylusDrawing = (mode == Mode.INK || mode == Mode.LASSO || mode == Mode.ERASE || mode == Mode.MARKUP) &&
                (activeType == PointerType.Stylus || activeType == PointerType.Eraser)
        if (stylusDrawing) return

        when (mode) {
            Mode.INK, Mode.LASSO -> {
                controller.geometryGuides.endInkStroke()
                engine.cancelStroke()
                ink.strokeEnded()
                // Given up for the gesture: gone at once on the other devices too
                if (mode == Mode.INK) {
                    controller.inkStrokeEnded()
                    val position = pressed.firstOrNull { it.id == activePointer }?.position ?: downPosition
                    reportActivity(position, inking = false, strokeCancelled = true)
                }
            }
            Mode.ERASE -> {
                controller.eraserManager.cancelGesture()
                live.setEraserCursor(null, 0f)
            }
            Mode.MARKUP -> controller.textMarkupManager.cancelMarking()
            Mode.SELECTION_MOVE, Mode.SELECTION_SCALE, Mode.SELECTION_ROTATE,
            Mode.TEXT_RESIZE_LEFT, Mode.TEXT_RESIZE_RIGHT -> {
                controller.selectionManager.applyTransformation()
            }
            Mode.TEXT_BOX -> live.textBoxDraft = null
            Mode.SHAPE -> controller.shapeInserter.cancel()
            Mode.SHAPE_HANDLE -> {
                activeShapeHandle = null
                controller.shapeEditing.cancelHandle()
            }
            Mode.GUIDE_DRAG -> activeGuideHit = null
            Mode.PAGE_REORDER -> return
            else -> {}
        }
        longPressCandidate = false
        mode = Mode.PINCH
        controller.viewport.camera.onDragStart()
        val p1 = pressed[0].position
        val p2 = pressed[1].position
        pinchCentroid = (p1 + p2) / 2f
        pinchDistance = (p1 - p2).getDistance().coerceAtLeast(1f)
        initialPinchDistance = pinchDistance
        isPinchZooming = false
        pinchPanPosition = Offset.Zero
        pinchVelocityTracker.resetTracking()
        val time = pressed[0].uptimeMillis
        pinchVelocityTracker.addPosition(time, pinchPanPosition)
        lastPinchSampleTime = time
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
                if (!strokeSnapped) {
                    for (h in change.historical) {
                        addInkInput(change, h.position, h.uptimeMillis, change.pressure)
                        predictor.record(h.position, h.uptimeMillis)
                    }
                    addInkInput(change, position, change.uptimeMillis, change.pressure)
                    predictor.record(position, change.uptimeMillis)
                    predictInk(change.pressure)
                    inkBrush?.let { ink.strokeChanged(engine, it.color, inkPageIndex) }
                    if (mode == Mode.INK) followHold(position, change.uptimeMillis)
                } else if (mode == Mode.INK) {
                    // The shape that snapped follows the pen still down
                    val pen = controller.viewport.screenToPageMm(inkPageIndex, position)
                    if (pen != null && controller.shapeSnapper.adjust(engine, Point(pen.x, pen.y))) {
                        inkBrush?.let { ink.strokeChanged(engine, it.color, inkPageIndex, replaced = true) }
                        reportActivity(position, inking = true)
                    }
                }
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
                controller.viewport.camera.onDrag(delta.x, delta.y, 1f, position.x, position.y)
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
                val scale = controller.viewport.camera.getCurrentScale().coerceAtLeast(0.0001f)
                editorMoveOffset += (position - lastPosition) / scale
                moveEditorBox()
                updateAutoScroll(position)
            }

            Mode.EDITOR_RESIZE_LEFT, Mode.EDITOR_RESIZE_RIGHT -> {
                val editor = controller.textEditorManager
                val left = mode == Mode.EDITOR_RESIZE_LEFT
                val pxPerMm = controller.viewport.pixelsPerMm(editor.activeTextPageIndex).coerceAtLeast(0.01f)
                val mm = controller.viewport.screenToPageMm(editor.activeTextPageIndex, position)
                if (editor.rotation == 0f && mm != null) {
                    // The handle is on the frame, its padding away from the text
                    val padding = FrameStyle.PADDING_DP * controller.viewport.density / pxPerMm
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

            Mode.GUIDE_DRAG -> {
                activeGuideHit?.let { controller.geometryGuides.drag(it, lastPosition, position, free = freeGesture || shiftHeld) }
                controller.requestRedraw()
            }

            Mode.TEXT_BOX -> live.textBoxDraft = boxBetween(downPosition, position)

            Mode.SHAPE_HANDLE -> {
                controller.viewport.screenToPageMm(inkPageIndex, position)?.let {
                    controller.shapeEditing.dragHandle(Point(it.x, it.y), forced = shiftHeld || shapeForcedByFinger, fromCenter = freeGesture, free = primaryHeld)
                }
                updateAutoScroll(position)
            }

            Mode.SHAPE -> {
                val inserter = controller.shapeInserter
                if (inserter.style.kind.isVertexEditable) {
                    if (inserter.isDrawingVertices) {
                        controller.viewport.screenToPageMm(inkPageIndex, position)?.let {
                            inserter.previewVertex(Point(it.x, it.y), free = primaryHeld)
                        }
                    } else if (movedBeyondSlop) {
                        controller.viewport.screenToPageMm(inkPageIndex, position)?.let {
                            inserter.update(Point(it.x, it.y), forced = shiftHeld || shapeForcedByFinger, fromCenter = freeGesture, free = primaryHeld)
                        }
                        updateAutoScroll(position)
                    }
                } else if (movedBeyondSlop) {
                    controller.viewport.screenToPageMm(inkPageIndex, position)?.let {
                        controller.shapeInserter.update(Point(it.x, it.y), forced = shiftHeld || shapeForcedByFinger, fromCenter = freeGesture, free = primaryHeld)
                    }
                    updateAutoScroll(position)
                }
            }

            Mode.PLACE_ITEM -> {
                if (movedBeyondSlop) controller.placementManager.moveItem(placementItem, position, placementGrab)
            }

            Mode.TEXT_TAP, Mode.PLACE_TAP, Mode.EDITOR_TAP -> {
                if (movedBeyondSlop && mode == Mode.TEXT_TAP && activeType != PointerType.Touch) {
                    // A pen or a mouse draws the box of the text; a finger moves the canvas
                    mode = Mode.TEXT_BOX
                    live.textBoxDraft = boxBetween(downPosition, position)
                } else if (movedBeyondSlop) {
                    mode = Mode.PAN
                    controller.viewport.camera.onDragStart()
                    val delta = position - downPosition
                    controller.viewport.camera.onDrag(delta.x, delta.y, 1f, position.x, position.y)
                }
            }

            Mode.SELECTION_MOVE -> {
                val selection = controller.selectionManager.currentSelection
                if (selection != null) {
                    val pxPerMm = controller.viewport.pixelsPerMm(selection.pageIndex).coerceAtLeast(0.01f)
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
                    controller.viewport.screenToPageMm(selection.pageIndex, position)?.let {
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
        // The others see the gesture at the rate of a display, not of the events of a stylus
        if (mode != Mode.PAN && mode != Mode.PINCH && mode != Mode.IGNORED && change.uptimeMillis - lastActivityTime >= ACTIVITY_INTERVAL_MS) {
            reportActivity(position, inking = mode == Mode.INK)
        }
        lastPosition = position
    }

    /**
     * Moves the box of the text being written to the corner of its drag (in the world), given in
     * the millimeters of the page it is on: the editor takes it to the page under its center.
     */
    private fun moveEditorBox() {
        val editor = controller.textEditorManager
        val worldToMm = Matrix2D()
        if (controller.viewport.pageMmToWorld(editor.activeTextPageIndex)?.invert(worldToMm) != true) return
        val corner = worldToMm.mapPoint(editorMoveOrigin.x + editorMoveOffset.x, editorMoveOrigin.y + editorMoveOffset.y)
        editor.moveTo(corner.x, corner.y, freeGesture)
    }

    private fun onPinchMove(pressed: List<PointerInputChange>, touchSlop: Float) {
        if (pressed.isEmpty()) return
        val time = pressed[0].uptimeMillis
        if (pressed.size == 1) {
            // One finger lifted: keep panning with the remaining one
            val p = pressed[0]
            val delta = p.position - p.previousPosition
            if (delta != Offset.Zero) {
                controller.viewport.camera.onDrag(delta.x, delta.y, 1f, p.position.x, p.position.y)
                pinchPanPosition += delta
                pinchVelocityTracker.addPosition(time, pinchPanPosition)
                lastPinchSampleTime = time
                pinchCentroid = p.position
                controller.requestRedraw()
            }
            return
        }
        val p1 = pressed[0].position
        val p2 = pressed[1].position
        val centroid = (p1 + p2) / 2f
        val distance = (p1 - p2).getDistance().coerceAtLeast(1f)
        val pressedCountChanged = pressed.any { it.changedToDownIgnoreConsumed() }
        if (!pressedCountChanged) {
            val delta = centroid - pinchCentroid
            val zoomSlop = touchSlop * 2f
            if (!isPinchZooming && abs(distance - initialPinchDistance) > zoomSlop) {
                isPinchZooming = true
                pinchDistance = if (distance > initialPinchDistance) initialPinchDistance + zoomSlop else initialPinchDistance - zoomSlop
            }
            val scale = if (isPinchZooming) distance / pinchDistance else 1f
            controller.viewport.camera.onDrag(delta.x, delta.y, scale, centroid.x, centroid.y)
            pinchPanPosition += delta
            pinchVelocityTracker.addPosition(time, pinchPanPosition)
            lastPinchSampleTime = time
            controller.requestRedraw()
        } else {
            initialPinchDistance = distance
            isPinchZooming = false
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
                val snappedShape = if (strokeSnapped && controller.shapeSnapper.snapToEditableShape) {
                    controller.shapeSnapper.currentSnappedShape
                } else null
                val brush = inkBrush
                if (snappedShape != null && brush != null) {
                    engine.cancelStroke()
                    ink.strokeEnded()
                    val (shapeValue, box) = ShapeConverter.toShapeElement(snappedShape, brush)
                    controller.customElementManager.add(
                        ShapeElementType,
                        shapeValue,
                        inkPageIndex,
                        io.github.valerioisufi.pageboard.model.geometry.Rect(
                            box.x,
                            box.y,
                            box.x + box.width.coerceAtLeast(0.5f),
                            box.y + box.height.coerceAtLeast(0.5f)
                        ),
                        box.rotation,
                        select = true
                    )
                } else {
                    if (activePointer == up.id && !strokeSnapped) addInkInput(up, position, up.uptimeMillis, up.pressure)
                    val mesh = engine.finishStroke()
                    ink.strokeEnded(mesh)
                    if (mesh != null && brush != null) controller.commitInkStroke(mesh, inkPageIndex, brush)
                }
                controller.shapeSnapper.release()
                controller.geometryGuides.endInkStroke()
                controller.inkStrokeEnded()
                reportActivity(position, inking = false)
            }

            Mode.LASSO -> {
                controller.geometryGuides.endInkStroke()
                val points = engine.inputs.toList()
                engine.cancelStroke()
                ink.strokeEnded()
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
                controller.viewport.camera.onRelease(velocity.x, velocity.y)
                controller.requestRedraw()
            }

            Mode.PINCH -> {
                if (up.uptimeMillis > lastPinchSampleTime) {
                    pinchVelocityTracker.addPosition(up.uptimeMillis, pinchPanPosition)
                }
                val velocity = pinchVelocityTracker.calculateVelocity()
                controller.viewport.camera.onRelease(velocity.x, velocity.y)
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

            Mode.EDITOR_MOVE, Mode.EDITOR_RESIZE_LEFT, Mode.EDITOR_RESIZE_RIGHT -> {
                controller.snapManager.clearGuides()
                // A tap on the frame, past a block of code ending (or starting) the text: a line by it
                if (mode == Mode.EDITOR_MOVE && wasTap) lineBesideEditedBlock(position)
            }

            Mode.TEXT_BOX -> {
                live.textBoxDraft = null
                onTextBoxDrawn(boxBetween(downPosition, position))
            }

            Mode.SHAPE -> {
                val inserter = controller.shapeInserter
                if (inserter.style.kind.isVertexEditable) {
                    if (wasTap) {
                        val isDouble = up.uptimeMillis - lastTapTime <= doubleTapTimeout &&
                            (position - lastTapPosition).getDistance() <= touchSlop * 2f
                        if (inserter.isDrawingVertices) {
                            if (isDouble) {
                                inserter.finishVertices()
                            } else {
                                controller.viewport.screenToPageMm(inkPageIndex, position)?.let {
                                    inserter.addVertex(inkPageIndex, Point(it.x, it.y), free = primaryHeld)
                                }
                            }
                        } else {
                            val shape = shapeAt(position)
                            when {
                                shape != null -> controller.selectionManager.selectCustomElement(shape.first, shape.second)
                                droppedSelection -> Unit
                                else -> controller.viewport.screenToPageMm(inkPageIndex, downPosition)?.let {
                                    inserter.addVertex(inkPageIndex, Point(it.x, it.y), free = primaryHeld)
                                }
                            }
                        }
                    } else if (!inserter.isDrawingVertices) {
                        inserter.commit()
                    }
                } else if (wasTap) {
                    inserter.cancel()
                    // A tap on a shape selects it; one dropping a selection only drops it
                    val shape = shapeAt(position)
                    when {
                        shape != null -> controller.selectionManager.selectCustomElement(shape.first, shape.second)
                        droppedSelection -> Unit
                        else -> controller.viewport.screenToPageMm(inkPageIndex, downPosition)?.let { inserter.insertAt(inkPageIndex, Point(it.x, it.y)) }
                    }
                } else {
                    inserter.commit()
                }
                shapeForcedByFinger = false
            }

            Mode.SHAPE_HANDLE -> {
                val handle = activeShapeHandle
                activeShapeHandle = null
                controller.shapeEditing.endHandle()
                if (wasTap && handle is ShapeHandle.Vertex) {
                    val isDoubleVertexTap = up.uptimeMillis - lastVertexTapTime <= doubleTapTimeout &&
                        lastVertexTapIndex == handle.index
                    if (isDoubleVertexTap) {
                        controller.shapeEditing.deleteVertex(handle.index)
                        lastVertexTapIndex = -1
                        lastVertexTapTime = 0L
                    } else {
                        lastVertexTapIndex = handle.index
                        lastVertexTapTime = up.uptimeMillis
                    }
                }
                shapeForcedByFinger = false
            }

            Mode.GUIDE_DRAG -> activeGuideHit = null

            Mode.PLACE_TAP -> {
                if (wasTap) controller.placementManager.placeAt(position)
            }

            Mode.PLACE_ITEM -> {
                if (wasTap) controller.placementManager.confirm()
            }

            Mode.EDITOR_TAP -> {
                // Just past a block of code ending (or starting) this text: a line by it; on another
                // text: saves this one and opens that one; elsewhere: saves this one
                if (wasTap && !lineBesideEditedBlock(position) && !editTextAt(position)) controller.textEditorManager.finishEditing()
            }

            else -> {}
        }

        // Double tap on a text block: edit it (whatever the active tool)
        val editorMode = mode == Mode.EDITOR_TAP || mode == Mode.EDITOR_MOVE || mode == Mode.EDITOR_RESIZE_LEFT || mode == Mode.EDITOR_RESIZE_RIGHT
        if (wasTap && mode != Mode.IGNORED && mode != Mode.PAGE_REORDER && !editorMode) {
            val isDouble = up.uptimeMillis - lastTapTime <= doubleTapTimeout &&
                    (position - lastTapPosition).getDistance() <= touchSlop * 2f
            if (isDouble && mode != Mode.TEXT_TAP && mode != Mode.PLACE_TAP && mode != Mode.PLACE_ITEM) {
                editTextAt(position)
            }
            lastTapTime = up.uptimeMillis
            lastTapPosition = position
        }

        mode = Mode.NONE
        activePointer = null
    }

    /** The shape under [position] (screen pixels), on top, with the index of its page: hit by its outline, or its inside when filled. */
    private fun shapeAt(position: Offset): Pair<Int, CustomElement>? {
        val pageIndex = controller.viewport.findPageIndexAtScreen(position) ?: return null
        val page = controller.document.pages.getOrNull(pageIndex) ?: return null
        val at = controller.viewport.screenToPageMm(pageIndex, position) ?: return null
        val elements = controller.engines.elements
        val shape = page.stack.lastOrNull { element ->
            element is CustomElement && element.type == ShapeElementType.id &&
                !page.isElementLocked(element) && !controller.hiddenElements.isHidden(element.id) && elements.hits(element, at.x, at.y)
        } as? CustomElement ?: return null
        return pageIndex to shape
    }

    /**
     * Follows the pen at [position] at [time] while it draws: it stops where it stays within a few
     * dp, and held there long enough the stroke becomes its shape (here when the pen sends events
     * while still, by the timer of [run] when it does not).
     */
    private fun followHold(position: Offset, time: Long) {
        if ((position - holdPosition).getDistance() > ShapeSnapper.HOLD_SLOP_DP * controller.viewport.density) {
            holdPosition = position
            holdTime = time
            holdTried = false
        } else if (holdCandidate && time - holdTime >= ShapeSnapper.HOLD_MS) {
            onHold()
        }
    }

    /**
     * The pen held still at the end of the stroke being drawn: the stroke becomes its shape, if it
     * looks like one. For the other devices it is a new stroke, its inputs sent from the first.
     */
    private fun onHold() {
        holdTried = true
        val brush = inkBrush ?: return
        if (mode != Mode.INK || !controller.shapeSnapper.snap(engine, inkPageIndex, brush, inkInputType)) return
        strokeSnapped = true
        strokeCount++
        // The pen held still is no latency of the ink
        controller.inkStrokeEnded()
        ink.strokeChanged(engine, brush.color, inkPageIndex, replaced = true)
        reportActivity(lastPosition, inking = true)
    }

    private fun onLongPress() {
        longPressCandidate = false
        if (movedBeyondSlop) return
        val onSelection = mode == Mode.SELECTION_MOVE
        when (mode) {
            Mode.INK, Mode.LASSO -> {
                controller.geometryGuides.endInkStroke()
                engine.cancelStroke()
                ink.strokeEnded()
                if (mode == Mode.INK) reportActivity(downPosition, inking = false, strokeCancelled = true)
            }
            Mode.ERASE -> controller.eraserManager.commitGesture()
            Mode.PAN -> controller.viewport.camera.onRelease(0f, 0f)
            Mode.MARKUP -> controller.textMarkupManager.cancelMarking()
            // Not moved: put back at its level
            Mode.SELECTION_MOVE -> controller.selectionManager.applyTransformation()
            else -> {}
        }
        mode = Mode.IGNORED
        // A mark or the text of a PDF under the finger (not under the selection): select it
        clearTextSelectionOnUp = false
        if (!onSelection && controller.textMarkupManager.selectAt(downPosition, textTolerancePx(activeType))) {
            controller.viewport.camera.stopAllAnimations()
            return
        }
        if (controller.isDrawingMode && !controller.toolManager.isPanTool) showContextMenu(downPosition, fromLongPress = true)
    }

    /**
     * Opens the context menu at [position]: on repeated long-press at the same spot ([fromLongPress]),
     * cycles downward through overlapping unlocked elements in stack order (`top -> middle -> bottom
     * -> wrap`); exposes a locked element target for Unlock when present under the pointer.
     */
    private fun showContextMenu(position: Offset, fromLongPress: Boolean) {
        val pageIndex = controller.viewport.findPageIndexAtScreen(position) ?: return
        controller.pageManager.contextMenuTargetPageIndex = pageIndex
        val page = controller.document.pages.getOrNull(pageIndex)
        val mm = controller.viewport.screenToPageMm(pageIndex, position)
        controller.lockedElementForMenu = null
        val selection = controller.selectionManager.currentSelection
        val onSelection = selection != null && SelectionFrame.compute(controller, selection)?.contains(position) == true
        if (page != null && mm != null) {
            val density = controller.viewport.density
            val pxPerMm = controller.viewport.pixelsPerMm(pageIndex).coerceAtLeast(0.01f)
            val strokeToleranceMm = STACK_CYCLE_STROKE_DP * density / pxPerMm
            val hits = controller.selectionManager.hitTestStack(pageIndex, mm, strokeToleranceMm)
            val prevPos = lastLongPressScreenPos
            val isRepeatedSameSpot = fromLongPress &&
                lastLongPressPageId == page.id &&
                prevPos != null &&
                (position - prevPos).getDistance() <= STACK_CYCLE_RADIUS_DP * density
            val topLocked = hits.firstOrNull()?.takeIf { page.isElementLocked(it) }
            val unlockedHits = hits.filterNot { page.isElementLocked(it) }
            if (topLocked != null) controller.lockedElementForMenu = page to topLocked
            when {
                isRepeatedSameSpot && unlockedHits.isNotEmpty() -> {
                    val currentIdx = unlockedHits.indexOfFirst { it.id == lastLongPressElementId }
                    val nextIdx = if (currentIdx >= 0) (currentIdx + 1) % unlockedHits.size else 0
                    val picked = unlockedHits[nextIdx]
                    lastLongPressPageId = page.id
                    lastLongPressScreenPos = position
                    lastLongPressElementId = picked.id
                    controller.selectionManager.selectElement(pageIndex, picked)
                }
                onSelection && selection.elements().any { !page.isElementLocked(it) } -> {
                    if (fromLongPress) {
                        lastLongPressPageId = page.id
                        lastLongPressScreenPos = position
                        lastLongPressElementId = selection.elements().firstOrNull()?.id
                    }
                }
                unlockedHits.isNotEmpty() -> {
                    val picked = unlockedHits.first()
                    lastLongPressPageId = page.id
                    lastLongPressScreenPos = position
                    lastLongPressElementId = picked.id
                    controller.selectionManager.selectElement(pageIndex, picked)
                }
                else -> {
                    lastLongPressPageId = page.id
                    lastLongPressScreenPos = position
                    lastLongPressElementId = null
                    controller.selectionManager.clearSelection()
                }
            }
        }
        controller.contextMenuPosition = position
        controller.viewport.camera.stopAllAnimations()
    }

    // ==========================================================
    // Text tool
    // ==========================================================

    private fun onTextToolTap(position: Offset) {
        if (editTextAt(position)) return
        val pageIndex = controller.viewport.findPageIndexAtScreen(position) ?: return
        val mm = controller.viewport.screenToPageMm(pageIndex, position) ?: return
        controller.textEditorManager.startNewText(pageIndex, mm.x, mm.y, freeGesture)
    }

    /** Starts a new text in the box drawn on the screen [box] with the text tool. */
    private fun onTextBoxDrawn(box: Rect) {
        val pageIndex = controller.viewport.findPageIndexAtScreen(box.topLeft) ?: controller.viewport.findPageIndexAtScreen(box.center) ?: return
        val topLeft = controller.viewport.screenToPageMm(pageIndex, box.topLeft) ?: return
        val bottomRight = controller.viewport.screenToPageMm(pageIndex, box.bottomRight) ?: return
        controller.textEditorManager.startDrawnText(pageIndex, Rect(topLeft, bottomRight))
    }

    /**
     * A tap at [position] just past the edge of the text being written by a block of code that ends
     * or starts it: a line by the block (see `TextEditorManager.lineBesideBlockAt`), within
     * [BLOCK_LINE_REACH_DP] of the edge on the screen.
     * @return `true` if the line was added.
     */
    private fun lineBesideEditedBlock(position: Offset): Boolean {
        val editor = controller.textEditorManager
        val pageIndex = editor.activeTextPageIndex
        val mm = controller.viewport.screenToPageMm(pageIndex, position) ?: return false
        val past = controller.viewport.screenToPageMm(pageIndex, position + Offset(BLOCK_LINE_REACH_DP * controller.viewport.density, 0f)) ?: return false
        return editor.lineBesideBlockAt(mm.x, mm.y, reachMm = (past - mm).getDistance())
    }

    /**
     * Opens the editor on the text block under [position] (not the one being written), the caret
     * on the character there.
     * @return `true` if a text block was found.
     */
    private fun editTextAt(position: Offset): Boolean {
        val pageIndex = controller.viewport.findPageIndexAtScreen(position) ?: return false
        val page = controller.document.pages.getOrNull(pageIndex) ?: return false
        val mm = controller.viewport.screenToPageMm(pageIndex, position) ?: return false
        val edited = controller.textEditorManager.activeTextItem
        val text = page.texts.lastOrNull {
            it !== edited && page.isElementVisible(it) && !page.isElementLocked(it) && it.containsPoint(mm.x, mm.y)
        } ?: return false
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
            val unit = scrollUnitPx(controller.viewport.density)
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
                // A wheel without phases (a mouse, the touchpads of Windows and Linux): a pause ends its gesture
                else -> {
                    if (change.uptimeMillis - lastWheelTime !in 0L..ScrollAxisLock.WHEEL_GESTURE_PAUSE_MS) wheelAxis.begin()
                    lastWheelTime = change.uptimeMillis
                    val camera = controller.viewport.camera
                    val locked = wheelAxis.filter(pan, camera.canMoveX, camera.canMoveY)
                    camera.onDrag(locked.x, locked.y, 1f, change.position.x, change.position.y)
                    camera.restoreToBounds(animated = false)
                    controller.requestRedraw()
                }
            }
        }
        change.consume()
    }

    /** Zooms by [factor] around [focus] at once (wheel with Ctrl / Cmd). */
    private fun zoomBy(factor: Float, focus: Offset) {
        controller.viewport.camera.onDrag(0f, 0f, factor, focus.x, focus.y)
        controller.viewport.camera.restoreToBounds(animated = false)
        controller.requestRedraw()
    }

    /** Phases of the two-finger scroll of a macOS trackpad, reported apart from its scroll events. */
    fun onTrackpadScrollPhase(phase: TrackpadScrollPhase, timeMillis: Long) {
        when (phase) {
            TrackpadScrollPhase.FINGERS_DOWN -> {
                trackpadFingersDown = true
                trackpadMomentum = false
                // Like a finger on a touch screen, the fingers on the trackpad stop the canvas
                if (controller.viewport.camera.isAnimating()) startTrackpadPan()
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
        controller.viewport.camera.onDragStart()
        trackpadVelocityTracker.resetTracking()
        trackpadPanPosition = Offset.Zero
        lastTrackpadSampleTime = 0L
        trackpadAxis.begin()
    }

    /**
     * Moves the canvas with the fingers, beyond its edges too (rubber band), on the axis of the
     * gesture until it breaks free of it.
     */
    private fun trackpadPanBy(delta: Offset, change: PointerInputChange) {
        val camera = controller.viewport.camera
        val locked = trackpadAxis.filter(delta, camera.canMoveX, camera.canMoveY)
        if (locked == Offset.Zero) return
        camera.onDrag(locked.x, locked.y, 1f, change.position.x, change.position.y)
        trackpadPanPosition += locked
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
        controller.viewport.camera.onRelease(velocity.x, velocity.y)
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
        if (phase == TrackpadPinchPhase.START) controller.viewport.camera.onDragStart()
        if (factor > 0f && factor != 1f) controller.viewport.camera.onDrag(0f, 0f, factor, focus.x, focus.y)
        if (phase == TrackpadPinchPhase.END) controller.viewport.camera.onRelease(0f, 0f)
        controller.requestRedraw()
    }

    // ==========================================================
    // Helpers
    // ==========================================================

    /**
     * Tells the controller where the pointer is ([screen]) and, when [inking], the stroke being
     * drawn, or that it was given up ([strokeCancelled], see [io.github.valerioisufi.pageboard.canvas.controller.CollaboratorPresence.activity]).
     */
    private fun reportActivity(screen: Offset, inking: Boolean, strokeCancelled: Boolean = false) {
        lastActivityTime = lastEventTime
        val pageIndex = if (inking) inkPageIndex else controller.viewport.findPageIndexAtScreen(screen)
        val page = pageIndex?.let { controller.document.pages.getOrNull(it) }
        val mm = pageIndex?.let { controller.viewport.screenToPageMm(it, screen) }
        if (page == null || mm == null) {
            controller.presence.reportActivity(if (strokeCancelled) EditorActivity(strokeCancelled = true) else null)
            return
        }
        val brush = inkBrush.takeIf { inking }
        controller.presence.reportActivity(
            EditorActivity(
                pageId = page.id,
                x = mm.x,
                y = mm.y,
                stroke = if (brush != null) strokeCount else 0,
                // Copied for the others only: a document no one follows does not pay it at every input
                ink = if (brush != null && controller.presence.isFollowed) engine.inputs.toList() else emptyList(),
                brush = brush,
                inputType = inkInputType,
                strokeCancelled = strokeCancelled
            )
        )
    }

    private fun addInkInput(change: PointerInputChange, screen: Offset, timeMs: Long, pressure: Float) {
        val projected = if (mode == Mode.INK) {
            controller.geometryGuides.snapInkPoint(screen, controller.viewport.density)
        } else {
            screen
        }
        val mm = controller.viewport.screenToPageMm(inkPageIndex, projected) ?: return
        val toolType = when (change.type) {
            PointerType.Stylus, PointerType.Eraser -> InputToolType.STYLUS
            PointerType.Touch -> InputToolType.TOUCH
            PointerType.Mouse -> InputToolType.MOUSE
            else -> InputToolType.UNKNOWN
        }
        if (engine.inputs.isEmpty()) inkInputType = toolType
        engine.addInput(mm.x, mm.y, pressure, timeMs, toolType)
        if (mode == Mode.INK) controller.inkLatencyMeter.inputAdded(timeMs)
    }

    /**
     * Predicts the stroke in progress up to a frame beyond its last input, drawn with the [pressure]
     * of that input (see [InkPredictor]): replaced at the next event, never an input of the stroke.
     */
    private fun predictInk(pressure: Float) {
        val points = predictor.predict(PREDICTION_MS).mapNotNull { predicted ->
            controller.viewport.screenToPageMm(inkPageIndex, predicted.position)?.let { StrokePoint(it.x, it.y, pressure, predicted.timeMs) }
        }
        // Also when nothing is predicted: an event without new inputs keeps the prediction before
        engine.predict(points)
        if (mode == Mode.INK) controller.inkLatencyMeter.predicted(points.lastOrNull()?.timestampMs ?: 0L)
    }

    /** How far from the text of the PDFs a pointer of [type] may be to select or mark it, in pixels. */
    private fun textTolerancePx(type: PointerType): Float =
        (if (type == PointerType.Touch) 12f else 6f) * controller.viewport.density

    /** The radius on screen of a stroke [sizeMm] wide drawn at [position]. */
    private fun inkRadiusPx(position: Offset, sizeMm: Float): Float {
        val pageIndex = controller.viewport.findPageIndexAtScreen(position) ?: controller.viewport.firstVisiblePageIndex() ?: 0
        return sizeMm / 2f * controller.viewport.pixelsPerMm(pageIndex)
    }

    private fun eraserRadiusPx(position: Offset): Float {
        val pageIndex = controller.viewport.findPageIndexAtScreen(position) ?: controller.viewport.firstVisiblePageIndex() ?: 0
        val diameterMm = controller.toolManager.activePreset(Tool.ERASER)?.sizeMm ?: 8f
        return diameterMm / 2f * controller.viewport.pixelsPerMm(pageIndex)
    }

    private fun updateAutoScroll(position: Offset) {
        val edge = 80f
        val height = controller.viewport.height
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
        controller.viewport.camera.onDrag(0f, delta, 1f, controller.viewport.width / 2f, controller.viewport.height / 2f)
        controller.viewport.camera.restoreToBounds(animated = false)
        when (mode) {
            Mode.SELECTION_MOVE -> {
                // Keep the selection under the finger while the camera moves
                val selection = controller.selectionManager.currentSelection
                if (selection != null) {
                    val pxPerMm = controller.viewport.pixelsPerMm(selection.pageIndex).coerceAtLeast(0.01f)
                    controller.selectionManager.moveBy(0f, -delta / pxPerMm, freeGesture)
                }
            }
            Mode.EDITOR_MOVE -> {
                // Keep the text being written under the pointer while the camera moves
                val scale = controller.viewport.camera.getCurrentScale().coerceAtLeast(0.0001f)
                editorMoveOffset += Offset(0f, -delta / scale)
                moveEditorBox()
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

/** The least time between two reports of the activity during a gesture (a frame of a 60 Hz display). */
private const val ACTIVITY_INTERVAL_MS = 16L

/** How far ahead of its last input the stroke in progress is predicted: a frame of a 60 Hz display. */
private const val PREDICTION_MS = 16L

/** How far past the edge of a text being written a tap adds a line by its block of code: a finger's width. */
private const val BLOCK_LINE_REACH_DP = 40f

private const val STACK_CYCLE_RADIUS_DP = 18f
private const val STACK_CYCLE_STROKE_DP = 12f
