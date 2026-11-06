package io.github.valerioisufi.drawview.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The selection of the text being written by a finger or a pen: the field is drawn scaled by the
 * zoom (see [pageScale]) and turned with its text, and the handles of a field, which Compose places
 * from its layout without the layers above it, would be off by both. So the editor takes the touches
 * on the text (see [touchSelectionGestures]) and draws the handles itself ([TouchHandles]), at their
 * size on the screen, where the text is.
 *
 * A finger: a tap puts the caret (and its handle), two select a word, a long press selects a word
 * and then follows the finger; a finger moving the text moves the canvas. A pen: the same, and a
 * pen pressed and dragged selects, as a mouse does.
 */
internal class TouchSelection {

    /** Which handles are shown. */
    enum class Handles { NONE, CARET, SELECTION }

    var handles: Handles by mutableStateOf(Handles.NONE)

    /** Whether the toolbar of the text (copy, cut, paste, select all) is shown. */
    var toolbar: Boolean by mutableStateOf(false)

    /** Whether a handle or a selection is being dragged: the toolbar waits for the end. */
    var dragging: Boolean by mutableStateOf(false)

    /** The value the touches gave last: another one (keys, a mouse, the bar) hides the handles. */
    var touched: TextFieldValue? = null

    /** Shows [value], set by a touch, with [handles] and the [toolbar] or not. */
    fun touch(value: TextFieldValue, handles: Handles, toolbar: Boolean) {
        touched = value
        this.handles = handles
        this.toolbar = toolbar
    }

    /**
     * The value of the text changed: by another way than a touch, the handles go. The composition
     * alone is no change: a keyboard composes the word of the caret again when the caret is put in it.
     */
    fun valueChanged(value: TextFieldValue) {
        val last = touched
        if (last != null && value.text == last.text && value.selection == last.selection) return
        touched = null
        handles = Handles.NONE
        toolbar = false
    }

    internal companion object {
        /** The size of a handle on the screen. */
        val HANDLE_SIZE = 22.dp

        /** The size of the area taking the touches of a handle. */
        val HANDLE_TARGET = 40.dp

        /** The range of the word of [text] at [offset] (letters and digits), or the offset alone. */
        fun wordAt(text: String, offset: Int): TextRange {
            fun inWord(i: Int) = i in text.indices && text[i].isLetterOrDigit()
            var start = offset.coerceIn(0, text.length)
            // At the end of a word: that word
            if (!inWord(start) && inWord(start - 1)) start--
            if (!inWord(start)) return TextRange(offset.coerceIn(0, text.length))
            while (inWord(start - 1)) start--
            var end = start
            while (inWord(end)) end++
            return TextRange(start, end)
        }
    }
}

/**
 * Takes the touches of a finger and of a pen on the field of the text being written, in the pixels
 * of its layout (put after [pageScale]), before the field (whose handles would be misplaced): see
 * [TouchSelection]. A mouse is left to the field; a pointer of no known type is a finger, the end
 * of a pen that erases a pen. A finger taken by the canvas (a pan, a pinch) is left to it.
 *
 * @param offsetAt the offset of the source under a point of the layout, `null` when not laid out.
 * @param value the value of the text now.
 * @param onValue sets the value of the text.
 * @param onTap the field takes the focus and the keyboard shows.
 */
internal fun Modifier.touchSelectionGestures(
    selection: TouchSelection,
    offsetAt: (Offset) -> Int?,
    value: () -> TextFieldValue,
    onValue: (TextFieldValue) -> Unit,
    onTap: () -> Unit
): Modifier = pointerInput(selection) {
    var lastTap = -1L
    var lastTapAt = Offset.Zero
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        // A pointer of no known type (an automation, an accessibility service) is taken as a finger
        if (down.type == PointerType.Mouse) return@awaitEachGesture
        if (down.isConsumed) return@awaitEachGesture
        down.consume()
        val pen = down.type == PointerType.Stylus || down.type == PointerType.Eraser
        val start = offsetAt(down.position) ?: return@awaitEachGesture
        val doubleTap = lastTap >= 0 && down.uptimeMillis - lastTap < viewConfiguration.doubleTapTimeoutMillis &&
            (down.position - lastTapAt).getDistance() < viewConfiguration.touchSlop * DOUBLE_TAP_SLOP
        var anchor = TextRange(start)
        var held = false
        var dragging = false
        fun select(range: TextRange, toolbar: Boolean) {
            val next = value().copy(selection = range)
            onValue(next)
            selection.touch(value(), if (range.collapsed) TouchSelection.Handles.CARET else TouchSelection.Handles.SELECTION, toolbar)
        }
        // A long press: its word, then the finger extends the selection
        fun hold() {
            held = true
            anchor = TouchSelection.wordAt(value().text, start)
            select(anchor, toolbar = false)
            selection.dragging = true
        }
        val longPress = viewConfiguration.longPressTimeoutMillis
        while (true) {
            val event = if (held || dragging) {
                awaitPointerEvent(PointerEventPass.Initial)
            } else {
                withTimeoutOrNull(longPress) { awaitPointerEvent(PointerEventPass.Initial) }
            }
            if (event == null) {
                hold()
                continue
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            // The canvas took it (a finger moving it, a second finger)
            if (change.isConsumed) {
                selection.dragging = false
                return@awaitEachGesture
            }
            change.consume()
            // Held long enough by the time of the event, as the canvas tells it
            if (!held && !dragging && change.uptimeMillis - down.uptimeMillis >= longPress &&
                (change.position - down.position).getDistance() <= viewConfiguration.touchSlop
            ) {
                hold()
            }
            if (!change.pressed) {
                selection.dragging = false
                when {
                    held || dragging -> {
                        val range = value().selection
                        selection.touch(value(), if (range.collapsed) TouchSelection.Handles.CARET else TouchSelection.Handles.SELECTION, toolbar = true)
                    }
                    doubleTap -> {
                        select(TouchSelection.wordAt(value().text, start), toolbar = true)
                        lastTap = -1L
                    }
                    else -> {
                        onTap()
                        // A tap on the caret shows the toolbar (paste), or hides it
                        val again = value().selection.collapsed && value().selection.start == start && selection.handles == TouchSelection.Handles.CARET
                        select(TextRange(start), toolbar = again && !selection.toolbar)
                        lastTap = change.uptimeMillis
                        lastTapAt = change.position
                    }
                }
                break
            }
            val moved = (change.position - down.position).getDistance() > viewConfiguration.touchSlop
            // Held or by a pen, the selection follows once the pointer moved (not on a move of the content under it)
            if (dragging || ((held || pen) && moved)) {
                dragging = true
                selection.dragging = true
                val end = offsetAt(change.position) ?: continue
                select(if (end < anchor.min) TextRange(anchor.max, end) else TextRange(anchor.min, end), toolbar = false)
            }
        }
    }
}

/**
 * The handles of the caret (after a tap) or of the selection of the text being written, drawn by
 * the editor at their size on the screen, at their place: [toScreen] maps a point of the layout of
 * the field to the screen (its place, its rotation, the zoom), [toLayout] back. Dragging one moves
 * its end of the selection to the offset under it ([offsetAt]).
 *
 * @param value the value of the text now.
 * @param caretRect the caret at an offset of the source, in the pixels of the layout.
 */
@Composable
internal fun TouchHandles(
    selection: TouchSelection,
    value: () -> TextFieldValue,
    caretRect: (Int) -> Rect?,
    toScreen: (Offset) -> Offset,
    toLayout: (Offset) -> Offset,
    offsetAt: (Offset) -> Int?,
    onValue: (TextFieldValue) -> Unit,
    color: Color
) {
    val shown = value()
    fun move(range: (TextRange, Int) -> TextRange): (Int) -> Unit = { offset ->
        onValue(value().let { it.copy(selection = range(it.selection, offset)) })
        selection.touched = value()
    }
    when (selection.handles) {
        TouchSelection.Handles.NONE -> Unit
        TouchSelection.Handles.CARET -> if (shown.selection.collapsed) {
            Handle(HandleKind.CARET, shown.selection.start, caretRect, toScreen, toLayout, offsetAt, selection, color, "text-handle-caret", move { _, offset -> TextRange(offset) })
        }
        TouchSelection.Handles.SELECTION -> if (!shown.selection.collapsed) {
            val startFirst = shown.selection.start <= shown.selection.end
            Handle(if (startFirst) HandleKind.START else HandleKind.END, shown.selection.start, caretRect, toScreen, toLayout, offsetAt, selection, color, "text-handle-start", move { range, offset -> TextRange(offset, range.end) })
            Handle(if (startFirst) HandleKind.END else HandleKind.START, shown.selection.end, caretRect, toScreen, toLayout, offsetAt, selection, color, "text-handle-end", move { range, offset -> TextRange(range.start, offset) })
        }
    }
}

private enum class HandleKind { CARET, START, END }

@Composable
private fun Handle(
    kind: HandleKind,
    offset: Int,
    caretRect: (Int) -> Rect?,
    toScreen: (Offset) -> Offset,
    toLayout: (Offset) -> Offset,
    offsetAt: (Offset) -> Int?,
    selection: TouchSelection,
    color: Color,
    tag: String = "text-handle-caret",
    onMove: (Int) -> Unit
) {
    val rect = caretRect(offset) ?: return
    val currentRect by rememberUpdatedState(rect)
    val currentOffsetAt by rememberUpdatedState(offsetAt)
    val currentMove by rememberUpdatedState(onMove)
    var placed by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Box(
        modifier = Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    // Hanging from the bottom of the caret: under it, or on the side of the text it ends
                    val anchor = toScreen(Offset(rect.left, rect.bottom))
                    val target = TouchSelection.HANDLE_TARGET.toPx()
                    val handle = TouchSelection.HANDLE_SIZE.toPx()
                    val x = when (kind) {
                        HandleKind.CARET -> anchor.x - target / 2f
                        HandleKind.START -> anchor.x - (target + handle) / 2f
                        HandleKind.END -> anchor.x - (target - handle) / 2f
                    }
                    // The drop of the caret is turned: its point is above its circle
                    val point = if (kind == HandleKind.CARET) handle / 2f * (SQRT_2 - 1f) else 0f
                    placeable.place(x.roundToInt(), (anchor.y - (target - handle) / 2f + point).roundToInt())
                }
            }
            .size(TouchSelection.HANDLE_TARGET)
            .testTag(tag)
            .onPlaced { placed = it }
            .pointerInput(kind) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    // The finger where the handle is placed, not on the handle: the handle moves
                    // with the caret under the finger
                    fun inParent(position: Offset) = (placed?.positionInParent() ?: Offset.Zero) + position
                    // The point of the text the handle hangs from, as far from the finger as at its down
                    val grab = toScreen(Offset(currentRect.left, currentRect.bottom)) - inParent(down.position)
                    selection.dragging = true
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val pointer = inParent(change.position) + grab
                        change.consume()
                        // In the middle of the line the handle hangs from
                        val inLayout = toLayout(pointer) - Offset(0f, currentRect.height / 2f)
                        currentOffsetAt(inLayout)?.let { currentMove(it) }
                    }
                    selection.dragging = false
                }
            }
    ) {
        Canvas(Modifier.size(TouchSelection.HANDLE_TARGET)) {
            val r = TouchSelection.HANDLE_SIZE.toPx() / 2f
            val center = Offset(size.width / 2f, (size.height - 2f * r) / 2f + r)
            // A drop: a circle with the corner pointing at the text
            val drop = Path().apply {
                addOval(Rect(center, r))
                addRect(Rect(Offset(if (kind == HandleKind.START) center.x else center.x - r, center.y - r), Size(r, r)))
            }
            if (kind == HandleKind.CARET) {
                rotate(45f, center) { drawPath(Path().apply { addOval(Rect(center, r)); addRect(Rect(Offset(center.x - r, center.y - r), Size(r, r))) }, color) }
            } else {
                drawPath(drop, color)
            }
        }
    }
}

/** How far from the first tap the second one of a double tap may be, in touch slops. */
private const val DOUBLE_TAP_SLOP = 4f

private const val SQRT_2 = 1.4142135f
