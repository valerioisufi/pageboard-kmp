package io.github.valerioisufi.drawview.sync.iroh

import io.github.valerioisufi.drawview.sync.protocol.SyncException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.first
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Two endpoints meeting through a relay, as devices on different networks do. */
class IrohRelayTest {
    private val bridge = assertNotNull(irohBridge, "the iroh bridge of this platform is missing")

    /**
     * The relay of the tests (`dev_relay`, plain HTTP on the loopback) while [body] runs; with
     * [token], it admits only the devices giving it.
     */
    private fun withRelay(token: String? = null, body: (url: String) -> Unit) {
        val binary = assertNotNull(System.getProperty("pageboard.iroh.devRelay"), "the relay of the tests")
        val command = listOf(binary) + if (token != null) listOf("0", token) else emptyList()
        val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        try {
            val url = assertNotNull(process.inputStream.bufferedReader().readLine(), "the address of the relay")
            body(url)
        } finally {
            process.outputStream.close()
            process.destroy()
        }
    }

    @Test
    fun twoEndpointsMeetThroughTheirRelayByIdentifierOnly() = withRelay { relay ->
        val first = bridge.bind(ByteArray(32) { 1 }, relays = relay, token = "", alpn = ALPN)
        val second = bridge.bind(ByteArray(32) { 2 }, relays = relay, token = "", alpn = ALPN)
        try {
            val address = assertNotNull(bridge.address(second, 10_000))
            assertTrue(relay in address, address)
            // What a device learns of another one: its identifier and its relay, no direct address
            val learned = address.split(' ').filter { ':' !in it || "://" in it }.joinToString(" ")
            val opened = bridge.connect(first, learned, 15_000)
            assertTrue(opened > 0, bridge.lastError())
            val accepted = bridge.accept(second, 15_000)
            assertTrue(accepted > 0, bridge.lastError())
            val out = bridge.openStream(opened)
            val frame = ByteArray(300_000) { 7 }
            assertEquals(IrohBridge.SENT, bridge.send(out, frame, 5_000))
            val back = bridge.acceptStream(accepted, 5_000)
            assertContentEquals(frame, bridge.receive(back, 10_000))
            assertNotEquals(IrohBridge.PATH_NONE, bridge.pathKind(opened))
        } finally {
            bridge.closeEndpoint(first)
            bridge.closeEndpoint(second)
        }
    }

    @Test
    fun devicesMeetingThroughTheRelayKeepTheDocumentsTheSame() = withRelay { relay ->
        irohSyncTest { scope ->
            // What a device learns of another one: its identifier and its relay
            threeDevicesKeepTheDocumentTheSame(scope, RelayConfig(listOf(relay))) { device ->
                device.address.split(' ').filter { ':' !in it || "://" in it }.joinToString(" ")
            }
        }
    }

    @Test
    fun membersReachEachOtherThroughTheRelayWithoutTheOwner() = withRelay { relay ->
        irohSyncTest { scope -> membersReachEachOtherWithoutTheOwner(scope, RelayConfig(listOf(relay))) }
    }

    @Test
    fun aRelayWithATokenAdmitsTheDevicesGivingIt() = withRelay(token = "secret of the relay") { relay ->
        irohSyncTest { scope ->
            fun transport(token: String?) = IrohTransport(scope, IrohSecret.generate(), RelayConfig(listOf(relay), token), connectTimeoutMs = 3_000)
            /** The address of [transport] as the others learn it: its identifier and its relay. */
            suspend fun learned(transport: IrohTransport) =
                transport.start().split(' ').filter { ':' !in it || "://" in it }.joinToString(" ")

            val admitted = transport("secret of the relay")
            val address = learned(admitted)
            val client = transport("secret of the relay").apply { start() }
            client.connect(address).send("through the relay".encodeToByteArray())
            assertEquals("through the relay", admitted.incoming.first().receive()?.decodeToString())

            // Without the token the relay refuses it: the device is not reached there
            val stranger = transport("another")
            stranger.start(timeoutMs = 3_000)
            assertFailsWith<SyncException> { stranger.connect(address) }
            listOf(admitted, client, stranger).forEach { it.close() }
        }
    }

    private companion object {
        const val ALPN = "drawview/test"
    }
}
