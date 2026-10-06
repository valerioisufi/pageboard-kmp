package io.github.valerioisufi.drawview.sync.lan

import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.nio.channels.ServerSocketChannel
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

class LanTransportJvmTest {

    @Test
    fun aPortAnotherApplicationListensOnOverIpv4IsLeftToIt() = runBlocking {
        ServerSocketChannel.open(StandardProtocolFamily.INET).use { other ->
            other.bind(InetSocketAddress("0.0.0.0", 0))
            val port = (other.localAddress as InetSocketAddress).port
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val transport = LanTransport(scope, port)
            try {
                // Listening there too would take only the IPv6 connections (macOS)
                assertNotEquals(port, transport.start())
            } finally {
                transport.close()
                scope.cancel()
            }
        }
    }
}
