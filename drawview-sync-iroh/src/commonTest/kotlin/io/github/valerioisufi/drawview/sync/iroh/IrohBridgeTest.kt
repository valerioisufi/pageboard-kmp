package io.github.valerioisufi.drawview.sync.iroh

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The iroh bridge of this platform between two endpoints of this process, directly. */
class IrohBridgeTest {
    private val bridge = assertNotNull(irohBridge, "the iroh bridge of this platform is missing")
    private val endpoints = mutableListOf<Long>()

    @AfterTest
    fun closeTheEndpoints() {
        endpoints.forEach(bridge::closeEndpoint)
    }

    private fun key(seed: Int) = ByteArray(32) { (seed + it).toByte() }

    private fun bind(seed: Int): Long {
        val endpoint = bridge.bind(key(seed), relays = "", token = "", alpn = ALPN)
        assertTrue(endpoint > 0, bridge.lastError())
        endpoints += endpoint
        return endpoint
    }

    /** A stream between two endpoints: its end on the side that connected, its end on the other. */
    private fun stream(): Pair<Long, Long> {
        val first = bind(1)
        val second = bind(2)
        val address = assertNotNull(bridge.address(second, 5_000))
        val opened = bridge.connect(first, address, 15_000)
        assertTrue(opened > 0, bridge.lastError())
        val accepted = bridge.accept(second, 15_000)
        assertTrue(accepted > 0, bridge.lastError())
        assertEquals(bridge.id(first), bridge.remoteId(accepted))
        val out = bridge.openStream(opened)
        assertTrue(out > 0, bridge.lastError())
        // The other side sees the stream with its first frame
        assertEquals(IrohBridge.SENT, bridge.send(out, "hello".encodeToByteArray(), 5_000))
        val back = bridge.acceptStream(accepted, 5_000)
        assertTrue(back > 0, bridge.lastError())
        assertContentEquals("hello".encodeToByteArray(), bridge.receive(back, 5_000))
        assertEquals(IrohBridge.PATH_DIRECT, bridge.pathKind(opened))
        return out to back
    }

    @Test
    fun framesTravelBothWaysWhole() {
        val (out, back) = stream()
        repeat(50) { i ->
            val frame = ByteArray(i * 37) { (it + i).toByte() }
            assertEquals(IrohBridge.SENT, bridge.send(out, frame, 5_000))
            assertContentEquals(frame, bridge.receive(back, 5_000))
            assertEquals(IrohBridge.SENT, bridge.send(back, frame, 5_000))
            assertContentEquals(frame, bridge.receive(out, 5_000))
        }
        val big = ByteArray(1_500_000) { (it % 253).toByte() }
        assertEquals(IrohBridge.SENT, bridge.send(out, big, 5_000))
        assertContentEquals(big, bridge.receive(back, 10_000))
    }

    @Test
    fun aReceiveWithNothingTimesOutAndCanBeMadeAgain() {
        val (out, back) = stream()
        assertNull(bridge.receive(back, 100))
        assertFalse(bridge.isEnded(back))
        assertEquals(IrohBridge.SENT, bridge.send(out, "later".encodeToByteArray(), 5_000))
        assertContentEquals("later".encodeToByteArray(), bridge.receive(back, 5_000))
    }

    @Test
    fun aStreamClosedGivesItsLastFramesThenItsEnd() {
        val (out, back) = stream()
        repeat(5) { i -> assertEquals(IrohBridge.SENT, bridge.send(out, ByteArray(1000) { i.toByte() }, 5_000)) }
        bridge.closeStream(out)
        repeat(5) { i -> assertContentEquals(ByteArray(1000) { i.toByte() }, bridge.receive(back, 5_000)) }
        assertNull(bridge.receive(back, 5_000))
        assertTrue(bridge.isEnded(back))
        // The number of a stream closed is refused
        assertEquals(IrohBridge.SEND_CLOSED, bridge.send(out, byteArrayOf(1), 100))
    }

    @Test
    fun anEndpointClosedRefusesItsCalls() {
        val endpoint = bridge.bind(key(9), relays = "", token = "", alpn = ALPN)
        bridge.closeEndpoint(endpoint)
        assertEquals(IrohBridge.CLOSED, bridge.accept(endpoint, 100))
        assertNull(bridge.address(endpoint, 100))
        assertEquals(IrohBridge.ERROR, bridge.bind(ByteArray(3), relays = "", token = "", alpn = ALPN))
        assertTrue(bridge.lastError().isNotEmpty())
    }

    private companion object {
        const val ALPN = "drawview/test"
    }
}
