package io.github.valerioisufi.pageboard.ui.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@Composable
internal actual fun immersiveSystemBars(): HiddenSystemBars {
    val view = LocalView.current
    // A cutout on the top edge keeps the room of the status bar taken: it stays, with its clock.
    // Read as the insets change, so that a rotation moving the cutout to a side hides it
    val cutoutOnTop = WindowInsets.displayCutout.getTop(LocalDensity.current) > 0
    val window = view.context.findActivity()?.window ?: return HiddenSystemBars.NONE
    DisposableEffect(view, cutoutOnTop) {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        fun hide() {
            if (cutoutOnTop) {
                controller.show(WindowInsetsCompat.Type.statusBars())
                controller.hide(WindowInsetsCompat.Type.navigationBars())
            } else {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        hide()
        // Under Android 11 a menu or a dialog (a window of its own) shows the bars and clears the
        // flags hiding them: hidden again when the editor gets the focus back
        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus -> if (hasFocus) view.post { hide() } }
        view.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
        onDispose {
            view.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    return if (cutoutOnTop) HiddenSystemBars.NAVIGATION else HiddenSystemBars.ALL
}

@Composable
internal actual fun PopupSystemBarsEffect() {
    val hidden = LocalHiddenSystemBars.current
    // From Android 11 the bars hidden by the window of the editor stay hidden under a popup
    if (hidden == HiddenSystemBars.NONE || Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return
    val view = LocalView.current
    DisposableEffect(view, hidden) {
        @Suppress("DEPRECATION")
        val flags = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            (if (hidden == HiddenSystemBars.ALL) View.SYSTEM_UI_FLAG_FULLSCREEN else 0)
        @Suppress("DEPRECATION")
        view.systemUiVisibility = view.systemUiVisibility or flags
        onDispose { }
    }
}

@Composable
internal actual fun popupTakesFocus(): Boolean =
    LocalHiddenSystemBars.current == HiddenSystemBars.NONE || Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
