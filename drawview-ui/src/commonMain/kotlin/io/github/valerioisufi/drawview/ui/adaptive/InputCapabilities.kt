package io.github.valerioisufi.drawview.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * What the platform tells of the input before any event is seen: the first guess of
 * [InputCapabilities].
 *
 * @param touch a touch screen.
 * @param stylus a stylus (an S Pen, an Apple Pencil the user draws with).
 * @param precisePointer a mouse or a trackpad, which hovers.
 * @param keyboard a hardware keyboard.
 */
@Immutable
public data class InputHints(
    val touch: Boolean = false,
    val stylus: Boolean = false,
    val precisePointer: Boolean = false,
    val keyboard: Boolean = false
) {
    public companion object {
        /** A phone. */
        public val Touch: InputHints = InputHints(touch = true)

        /** A tablet with its stylus. */
        public val Stylus: InputHints = InputHints(touch = true, stylus = true)

        /** A computer. */
        public val PointerAndKeyboard: InputHints = InputHints(precisePointer = true, keyboard = true)
    }
}

/** What the platform tells of its input devices, updated when it reports a change (a keyboard plugged in). */
@Composable
internal expect fun platformInputHints(): InputHints

/** Whether [this] key event comes from a hardware keyboard (not from the keys of a soft keyboard). */
internal expect fun KeyEvent.isFromHardwareKeyboard(): Boolean

/**
 * What the user has at hand, learned from the platform ([InputHints]) and from the events seen by
 * [observeInput]. The capabilities are only added: a stylus or a keyboard seen once is kept, so
 * that the interface does not change while the user moves from a finger to the stylus.
 */
@Stable
public class InputCapabilities(hints: InputHints = InputHints()) {

    /** A finger has been used, or the device has a touch screen. */
    internal var hasTouch by mutableStateOf(hints.touch)
        private set

    /** A stylus has been used, or the platform reports one. */
    internal var hasStylus by mutableStateOf(hints.stylus)
        private set

    /** A mouse or a trackpad has been used, or the platform reports one. */
    internal var hasPrecisePointer by mutableStateOf(hints.precisePointer)
        private set

    /** A hardware keyboard has been used, or the platform reports one. */
    public var hasKeyboard: Boolean by mutableStateOf(hints.keyboard)
        private set

    /** Whether a pointer can hover the controls (tooltips, cursors). */
    internal val canHover: Boolean get() = hasPrecisePointer

    /**
     * The smallest size of a control: 48 dp where a finger can touch it, 40 dp when it is reached
     * only by a precise pointer or a stylus.
     */
    internal val minimumInteractiveSize: Dp get() = if (hasTouch) 48.dp else 40.dp

    /** Adds what [hints] tell. */
    internal fun learn(hints: InputHints) {
        if (hints.touch) hasTouch = true
        if (hints.stylus) hasStylus = true
        if (hints.precisePointer) hasPrecisePointer = true
        if (hints.keyboard) hasKeyboard = true
    }

    /** A pointer of [type] has been used. */
    internal fun onPointer(type: PointerType) {
        when (type) {
            PointerType.Touch -> if (!hasTouch) hasTouch = true
            PointerType.Stylus, PointerType.Eraser -> if (!hasStylus) hasStylus = true
            PointerType.Mouse -> if (!hasPrecisePointer) hasPrecisePointer = true
        }
    }

    /** A key of a hardware keyboard has been pressed. */
    internal fun onKey() {
        if (!hasKeyboard) hasKeyboard = true
    }
}

/**
 * Tells [capabilities] the pointers and the keys of the content, seen before the content handles
 * them and left to it.
 */
internal fun Modifier.observeInput(capabilities: InputCapabilities): Modifier =
    pointerInput(capabilities) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { capabilities.onPointer(it.type) }
            }
        }
    }.onPreviewKeyEvent { event ->
        if (event.isFromHardwareKeyboard()) capabilities.onKey()
        false
    }
