package io.github.valerioisufi.drawview.data

import kotlinx.browser.window

internal actual fun localStorageAvailable(): Boolean = try {
    window.localStorage.length >= 0
} catch (_: Throwable) {
    false
}

internal actual fun localStorageGet(key: String): String? = try {
    window.localStorage.getItem(key)
} catch (_: Throwable) {
    null
}

internal actual fun localStorageSet(key: String, value: String): Boolean = try {
    window.localStorage.setItem(key, value)
    true
} catch (_: Throwable) {
    false
}

internal actual fun localStorageRemove(key: String) {
    try {
        window.localStorage.removeItem(key)
    } catch (_: Throwable) {
    }
}
