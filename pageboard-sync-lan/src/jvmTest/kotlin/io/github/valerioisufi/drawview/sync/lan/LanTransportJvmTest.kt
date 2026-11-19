package io.github.valerioisufi.drawview.sync.lan

import io.github.valerioisufi.drawview.sync.protocol.SyncException
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.StandardProtocolFamily
import java.nio.channels.ServerSocketChannel
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

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

    @Test
    fun aFrameIsReadInPartsAndALargerOneIsRefusedUnread() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = LanTransport(scope, 0)
        try {
            // Stuck otherwise (a socket not read): failed
            withTimeout(60_000) { framesOfOtherDevices(transport) }
        } finally {
            transport.close()
            scope.cancel()
        }
    }

    /** Frames of other devices through [transport]: one large, one refused, lengths announced only. */
    private suspend fun CoroutineScope.framesOfOtherDevices(transport: LanTransport) {
        val port = transport.start()
        fun open() = Socket("127.0.0.1", port).let { it to DataOutputStream(it.getOutputStream()) }

        // Larger than the first part read, in pieces
        val (socket, output) = open()
        val channel = transport.incoming.first()
        val frame = Random(1).nextBytes(3 * 1024 * 1024 + 7)
        // Written while it is read (more than the buffers of the sockets)
        val writing = launch(Dispatchers.IO) {
            output.writeInt(frame.size)
            for (start in frame.indices step 500_000) {
                output.write(frame, start, minOf(500_000, frame.size - start))
                output.flush()
            }
        }
        assertContentEquals(frame, channel.receive())
        writing.join()

        // A length announced larger than allowed: refused before its bytes
        output.writeInt(2048)
        output.flush()
        assertFailsWith<SyncException> { channel.receive(1024) }
        socket.close()

        // Large lengths announced and a few bytes: the memory is not taken for them (four times
        // 250 MiB is more than the heap of the tests), then gone
        val others = List(4) {
            val (other, otherOutput) = open()
            val otherChannel = transport.incoming.first()
            otherOutput.writeInt(250 * 1024 * 1024)
            otherOutput.write(ByteArray(10))
            otherOutput.flush()
            other to async { otherChannel.receive() }
        }
        delay(500)
        assertTrue(others.none { it.second.isCompleted })
        others.forEach { it.first.close() }
        others.forEach { assertNull(it.second.await()) }
    }
}
