package io.github.valerioisufi.pageboard.platform

import android.util.Log

internal actual fun writeWarning(message: String, error: Throwable?) {
    Log.w("Pageboard", message, error)
}
