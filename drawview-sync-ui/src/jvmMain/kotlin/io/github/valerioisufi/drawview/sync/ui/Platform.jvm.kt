package io.github.valerioisufi.drawview.sync.ui

import androidx.compose.runtime.Composable
import io.github.valerioisufi.drawview.sync.lan.JmdnsLanDiscovery
import io.github.valerioisufi.drawview.sync.lan.LanDiscovery
import java.net.InetAddress

internal actual fun createLanDiscovery(): LanDiscovery = JmdnsLanDiscovery()

internal actual fun localDeviceName(): String =
    System.getenv("COMPUTERNAME")
        ?: runCatching { InetAddress.getLocalHost().hostName.substringBefore('.') }.getOrNull()
        ?: System.getProperty("user.name")
        ?: "Desktop"

@Composable
internal actual fun rememberLocalNetworkAccess(): suspend () -> Boolean = { true }
