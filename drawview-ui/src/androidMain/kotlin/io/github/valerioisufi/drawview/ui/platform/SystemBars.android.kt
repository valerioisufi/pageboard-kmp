package io.github.valerioisufi.drawview.ui.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
internal actual fun ImmersiveSystemBarsEffect() {
    val view = LocalView.current
    // A cutout on the top edge keeps the room of the status bar taken: it stays, with its clock.
    // Read as the insets change, so that a rotation moving the cutout to a side hides it
    val cutoutOnTop = WindowInsets.displayCutout.getTop(LocalDensity.current) > 0
    DisposableEffect(view, cutoutOnTop) {
        val window = view.context.findActivity()?.window ?: return@DisposableEffect onDispose { }
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
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
