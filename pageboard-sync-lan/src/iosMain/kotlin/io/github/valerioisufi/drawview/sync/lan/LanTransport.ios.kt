package io.github.valerioisufi.drawview.sync.lan

// The sockets are IPv4 ones: a port taken is refused
internal actual fun isListenedOnIpv4(port: Int): Boolean = false
