package io.github.valerioisufi.drawview.sync.lan

import java.net.BindException
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.nio.channels.ServerSocketChannel

internal actual fun isListenedOnIpv4(port: Int): Boolean = try {
    ServerSocketChannel.open(StandardProtocolFamily.INET).use { it.bind(InetSocketAddress("0.0.0.0", port)) }
    false
} catch (_: BindException) {
    true
} catch (_: Exception) {
    false
}
