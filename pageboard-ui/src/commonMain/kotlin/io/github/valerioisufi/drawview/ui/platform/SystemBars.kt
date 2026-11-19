package io.github.valerioisufi.drawview.ui.platform

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

/** The system bars the editor hides. */
internal enum class HiddenSystemBars {
    /** None: outside of the editor, or where the platform has none. */
    NONE,

    /** The navigation bar (Android) or the home indicator (iOS): the status bar beside a notch stays. */
    NAVIGATION,

    /** The status bar and the navigation bar. */
    ALL
}

/** The system bars the editor around hides, kept hidden by the windows opened over it. */
internal val LocalHiddenSystemBars = compositionLocalOf { HiddenSystemBars.NONE }

/**
 * What the editor keeps clear of: the cutout, the keyboard and the system bars it does not hide
 * ([LocalHiddenSystemBars]). Those it hides come back only over it, with a swipe, or under
 * Android 11 while another window has the focus (a menu): their room would move its bars.
 */
@Composable
internal fun editorInsets(): WindowInsets = when (LocalHiddenSystemBars.current) {
    HiddenSystemBars.NONE -> WindowInsets.safeDrawing
    HiddenSystemBars.NAVIGATION -> WindowInsets.statusBars.union(WindowInsets.displayCutout).union(WindowInsets.ime)
    HiddenSystemBars.ALL -> WindowInsets.displayCutout.union(WindowInsets.ime)
}

/**
 * Gives the room of the system bars to the editor while it is shown, where it gains some: the
 * navigation bar (Android) and the home indicator (iOS) are hidden, the status bar only where no
 * notch, punch hole or island keeps its room taken anyway. They come back for a while with a
 * swipe, and are hidden again when the editor gets the focus back (after a menu or a dialog).
 * Returns the bars hidden, for [PopupSystemBarsEffect].
 */
@Composable
internal expect fun immersiveSystemBars(): HiddenSystemBars

/**
 * Keeps hidden, in a window opened over the editor (a menu, a popover, a dialog), the system bars
 * the editor hides ([LocalHiddenSystemBars]): under Android 11 the bars follow the window that has
 * the focus, and a window without the flags hiding them would show them while it is open.
 */
@Composable
internal expect fun PopupSystemBarsEffect()

/**
 * Whether a popup over the editor (a menu, a popover) takes the focus. Under Android 11, where the
 * editor hides the system bars, it does not: the bars follow the window that has the focus, and a
 * new one would show them for a moment, before [PopupSystemBarsEffect] hides them again. One
 * that does not take the focus is closed by the system back ([UnfocusedPopupGuard]) and takes no
 * text: a popup with a text field takes the focus anyway.
 */
@Composable
internal expect fun popupTakesFocus(): Boolean

/**
 * What a popup that does not take the focus misses, given beside it (not in it: the back and the
 * touches outside of it reach the window of the editor): the system back closes it, and a touch
 * outside of it closes it without reaching the editor (a stroke on the page), in a transparent
 * window under it.
 */
@Composable
internal fun UnfocusedPopupGuard(onDismiss: () -> Unit) {
    if (LocalNavigationEventDispatcherOwner.current != null) {
        NavigationBackHandler(
            state = rememberNavigationEventState(NavigationEventInfo.None),
            isBackEnabled = true,
            onBackCompleted = onDismiss
        )
    }
    val dismiss by rememberUpdatedState(onDismiss)
    Popup(popupPositionProvider = WindowOrigin, properties = PopupProperties(focusable = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        dismiss()
                    }
                }
        )
    }
}

/** A popup over the whole window. */
private object WindowOrigin : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
        IntOffset.Zero
}
