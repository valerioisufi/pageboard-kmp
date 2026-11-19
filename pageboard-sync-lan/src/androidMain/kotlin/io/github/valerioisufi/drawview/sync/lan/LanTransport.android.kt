package io.github.valerioisufi.drawview.sync.lan

// Linux refuses a port listened on over IPv4 to the dual-stack sockets
internal actual fun isListenedOnIpv4(port: Int): Boolean = false
