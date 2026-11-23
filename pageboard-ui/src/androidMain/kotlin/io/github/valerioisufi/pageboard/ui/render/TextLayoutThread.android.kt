package io.github.valerioisufi.pageboard.ui.render

internal actual fun <T> onTextLayoutThread(block: () -> T): T = block()
