package io.github.valerioisufi.drawview.sync.ui

import androidx.compose.runtime.Composable
import io.github.valerioisufi.drawview.sync.lan.BonjourLanDiscovery
import io.github.valerioisufi.drawview.sync.lan.LanDiscovery

internal actual fun createLanDiscovery(): LanDiscovery = BonjourLanDiscovery()

// iOS asks for the permission of the local network itself, the first time it is used
@Composable
internal actual fun rememberLocalNetworkAccess(): suspend () -> Boolean = { true }
