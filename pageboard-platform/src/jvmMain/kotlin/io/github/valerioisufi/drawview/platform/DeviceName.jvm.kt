package io.github.valerioisufi.drawview.platform

import java.net.InetAddress

internal actual fun localDeviceName(): String =
    System.getenv("COMPUTERNAME")
        ?: runCatching { InetAddress.getLocalHost().hostName.substringBefore('.') }.getOrNull()
        ?: System.getProperty("user.name")
        ?: "Desktop"
