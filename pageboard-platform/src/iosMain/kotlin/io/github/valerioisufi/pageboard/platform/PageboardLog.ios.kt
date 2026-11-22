package io.github.valerioisufi.pageboard.platform

import platform.Foundation.NSLog

internal actual fun writeWarning(message: String, error: Throwable?) {
    NSLog("Pageboard: %@", if (error == null) message else "$message: $error")
}
