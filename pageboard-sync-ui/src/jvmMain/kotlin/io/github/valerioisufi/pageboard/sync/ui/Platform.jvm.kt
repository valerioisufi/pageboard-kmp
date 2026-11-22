package io.github.valerioisufi.pageboard.sync.ui

import androidx.compose.runtime.Composable
import io.github.valerioisufi.pageboard.sync.lan.JmdnsLanDiscovery
import io.github.valerioisufi.pageboard.sync.lan.LanDiscovery
import io.github.valerioisufi.pageboard.sync.ui.generated.resources.Res
import io.github.valerioisufi.pageboard.sync.ui.generated.resources.sync_settings_macos
import org.jetbrains.compose.resources.StringResource

internal actual fun createLanDiscovery(): LanDiscovery = JmdnsLanDiscovery()

@Composable
internal actual fun rememberLocalNetworkAccess(): suspend () -> Boolean = { true }

// The desktop goes on working in the background
internal actual object BackgroundSync {
    actual fun update(active: Boolean, documents: Int) {}
}

// macOS asks for the local network (Sequoia and later); Windows and Linux do not
internal actual fun localNetworkSettings(): StringResource? =
    if (System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)) Res.string.sync_settings_macos else null
