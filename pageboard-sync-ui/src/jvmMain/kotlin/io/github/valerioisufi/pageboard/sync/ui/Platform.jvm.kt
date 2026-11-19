package io.github.valerioisufi.pageboard.sync.ui

import androidx.compose.runtime.Composable
import io.github.valerioisufi.pageboard.sync.lan.JmdnsLanDiscovery
import io.github.valerioisufi.pageboard.sync.lan.LanDiscovery

internal actual fun createLanDiscovery(): LanDiscovery = JmdnsLanDiscovery()

@Composable
internal actual fun rememberLocalNetworkAccess(): suspend () -> Boolean = { true }

// The desktop goes on working in the background
internal actual object BackgroundSync {
    actual fun update(active: Boolean, documents: Int) {}
}
