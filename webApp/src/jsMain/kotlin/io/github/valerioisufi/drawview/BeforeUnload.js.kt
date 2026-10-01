package io.github.valerioisufi.drawview

import kotlinx.browser.window

internal actual fun onBeforeUnload(action: () -> Unit) {
    window.addEventListener("beforeunload", { action() })
}
