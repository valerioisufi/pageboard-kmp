package io.github.valerioisufi.drawview.sync.iroh

import io.github.valerioisufi.drawview.sync.protocol.SyncException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class IrohTransportTest {

    /** Runs [block] in real time, with a scope for the work of the transports. */
    private fun irohTest(block: suspend (CoroutineScope) -> Unit) = runTest {
        withContext(Dispatchers.Default) {
            val work = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                withTimeout(60_000) { block(work) }
            } finally {
                work.cancel()
            }
        }
    }

    private fun transport(scope: CoroutineScope, connectTimeoutMs: Long = 15_000) =
        IrohTransport(scope, IrohSecret.generate(), RelayConfig.NONE, connectTimeoutMs)

    @Test
    fun framesTravelOverIroh() = irohTest { scope ->
        val server = transport(scope)
        val address = server.start()
        assertTrue(address.startsWith(assertNotNull(server.id)), address)
        val client = transport(scope)
        client.start()
        val outgoing = client.connect(address)

        // The other device sees the channel with its first frame
        val large = ByteArray(1_500_000) { (it % 251).toByte() }
        outgoing.send("hello".encodeToByteArray())
        outgoing.send(ByteArray(0))
        outgoing.send(large)
        val incoming = server.incoming.first()
        assertEquals("hello", incoming.receive()?.decodeToString())
        assertContentEquals(ByteArray(0), incoming.receive())
        assertContentEquals(large, incoming.receive())
        incoming.send("back".encodeToByteArray())
        assertEquals("back", outgoing.receive()?.decodeToString())

        // Closed on one side: the other one sees the end
        outgoing.close()
        assertNull(incoming.receive())
        client.close()
        server.close()
    }

    @Test
    fun aReceiveCancelledLetsGoAtOnce() = irohTest { scope ->
        val server = transport(scope)
        val address = server.start()
        val client = transport(scope).apply { start() }
        val outgoing = client.connect(address)
        outgoing.send("first".encodeToByteArray())
        val incoming = server.incoming.first()
        assertEquals("first", incoming.receive()?.decodeToString())

        // As the engine does with a connection silent for too long
        val started = TimeSource.Monotonic.markNow()
        assertNull(withTimeoutOrNull(100) { incoming.receive() })
        assertTrue(started.elapsedNow().inWholeMilliseconds < 1_000, "let go after ${started.elapsedNow()}")
        // The channel goes on
        outgoing.send("second".encodeToByteArray())
        assertEquals("second", incoming.receive()?.decodeToString())
        client.close()
        server.close()
    }

    @Test
    fun theChannelsToADeviceShareItsConnection() = irohTest { scope ->
        val server = transport(scope)
        val address = server.start()
        val client = transport(scope).apply { start() }
        val channels = List(3) { client.connect(address) }
        channels.forEachIndexed { i, channel -> channel.send("channel $i".encodeToByteArray()) }
        val received = List(3) { server.incoming.first() }.map { it.receive()?.decodeToString() }
        assertEquals(List(3) { "channel $it" }.toSet(), received.toSet())
        channels.forEach { it.close() }

        // The last channel closed its connection: a new one is opened
        val again = client.connect(address)
        again.send("again".encodeToByteArray())
        assertEquals("again", server.incoming.first().receive()?.decodeToString())
        client.close()
        server.close()
    }

    @Test
    fun aDeviceGoneCannotBeConnectedTo() = irohTest { scope ->
        val server = transport(scope)
        val address = server.start()
        server.close()
        val client = transport(scope, connectTimeoutMs = 2_000).apply { start() }
        assertFailsWith<SyncException> { client.connect(address) }
        client.close()
    }

    @Test
    fun aTransportNotStartedCannotConnect() = irohTest { scope ->
        val server = transport(scope)
        val address = server.start()
        assertFailsWith<SyncException> { transport(scope).connect(address) }
        server.close()
    }
}
