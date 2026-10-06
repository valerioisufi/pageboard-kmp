package io.github.valerioisufi.drawview.sync.ui

import androidx.compose.runtime.Composable
import io.github.valerioisufi.drawview.sync.lan.LanDiscovery

/** The discovery of the devices of the local network of this platform. */
internal expect fun createLanDiscovery(): LanDiscovery

/**
 * Asks for the permission to use the local network where the platform needs it (Android 17);
 * the function returns whether it is granted.
 */
@Composable
internal expect fun rememberLocalNetworkAccess(): suspend () -> Boolean
