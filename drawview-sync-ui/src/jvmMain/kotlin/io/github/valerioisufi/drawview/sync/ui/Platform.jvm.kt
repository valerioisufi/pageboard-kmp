package io.github.valerioisufi.drawview.sync.ui

import androidx.compose.runtime.Composable
import io.github.valerioisufi.drawview.sync.lan.JmdnsLanDiscovery
import io.github.valerioisufi.drawview.sync.lan.LanDiscovery

internal actual fun createLanDiscovery(): LanDiscovery = JmdnsLanDiscovery()

@Composable
internal actual fun rememberLocalNetworkAccess(): suspend () -> Boolean = { true }
