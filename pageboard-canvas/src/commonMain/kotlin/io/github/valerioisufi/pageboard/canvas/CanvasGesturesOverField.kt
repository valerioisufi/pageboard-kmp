package io.github.valerioisufi.pageboard.canvas

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import io.github.valerioisufi.pageboard.canvas.controller.PageboardController

/**
 * Gives the canvas under an element the gestures that move the camera, which never reach it
 * otherwise (a field over the canvas takes what falls on it): a wheel and a trackpad; a finger
 * dragged past the slop, before its long press; a second finger; the middle button of a mouse.
 *
 * What is left stays with the element: a tap, a long press and what follows it, a pen, the other
 * buttons of a mouse.
 */
public fun Modifier.canvasGesturesOverField(controller: PageboardController): Modifier = pointerInput(controller) {
    val touchSlop = viewConfiguration.touchSlop
    val longPressTimeout = viewConfiguration.longPressTimeoutMillis
    val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
    awaitPointerEventScope {
        // A finger that may become a gesture of the canvas, where and when it came down
        var finger: PointerId? = null
        var fingerAt = Offset.Zero
        var fingerTime = 0L
        // The gesture is the canvas's, and until all the pointers are up
        var taken = false
        // The gesture is the element's, until all the pointers are up
        var left = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val gestures = controller.canvasGestures ?: continue
            val moved = event.type == PointerEventType.Scroll ||
                event.type == PointerEventType.PanStart || event.type == PointerEventType.PanMove || event.type == PointerEventType.PanEnd ||
                event.type == PointerEventType.ScaleStart || event.type == PointerEventType.ScaleChange || event.type == PointerEventType.ScaleEnd
            val pressed = event.changes.filter { it.pressed }
            when {
                moved -> with(gestures) { handle(event, touchSlop, doubleTapTimeout) }
                taken -> {
                    with(gestures) { handle(event, touchSlop, doubleTapTimeout) }
                    if (pressed.isEmpty()) taken = false
                }
                pressed.isEmpty() -> {
                    finger = null
                    left = false
                }
                left -> {}
                finger == null -> {
                    val down = event.changes.firstOrNull { it.changedToDownIgnoreConsumed() } ?: continue
                    when {
                        down.type == PointerType.Mouse && event.buttons.isTertiaryPressed -> {
                            gestures.takeOver(pressed)
                            taken = true
                        }
                        down.type == PointerType.Touch -> {
                            finger = down.id
                            fingerAt = down.position
                            fingerTime = down.uptimeMillis
                        }
                        else -> left = true
                    }
                }
                else -> {
                    val current = pressed.firstOrNull { it.id == finger }
                    val time = pressed[0].uptimeMillis
                    if (time - fingerTime >= longPressTimeout) {
                        // Its long press: what it selects is the element's
                        finger = null
                        left = true
                    } else if (pressed.size >= 2 || (current != null && (current.position - fingerAt).getDistance() > touchSlop)) {
                        gestures.takeOver(pressed)
                        finger = null
                        taken = true
                    }
                }
            }
        }
    }
}
