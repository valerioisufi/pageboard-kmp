package io.github.valerioisufi.pageboard.sync.ui

import androidx.compose.runtime.Composable
import io.github.valerioisufi.pageboard.sync.lan.LanDiscovery

/** The discovery of the devices of the local network of this platform. */
internal expect fun createLanDiscovery(): LanDiscovery

/**
 * Asks for the permission to use the local network where the platform needs it (Android 17);
 * the function returns whether it is granted.
 */
@Composable
internal expect fun rememberLocalNetworkAccess(): suspend () -> Boolean

/**
 * Keeps the application working with the other devices once in the background while `active`
 * (connected to some, on `documents` documents): a foreground service on Android, the time the
 * system gives to finish on iOS; nothing to do on the desktop.
 */
internal expect object BackgroundSync {
    fun update(active: Boolean, documents: Int)
}
