@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.valerioisufi.drawview

internal actual fun onBeforeUnload(action: () -> Unit) = addBeforeUnloadListener(action)

private fun addBeforeUnloadListener(action: () -> Unit): Unit =
    js("{ window.addEventListener('beforeunload', () => action()); }")
