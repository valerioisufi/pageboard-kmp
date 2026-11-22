package io.github.valerioisufi.pageboard.sync.lan

import java.net.InetAddress
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [JmdnsLanDiscovery] following the address of the machine, on responders in memory. */
class JmdnsAddressTest {

    /** A responder on [address]: what it announces, the devices it finds, whether it is closed. */
    private class FakeResponder(val address: InetAddress) : MdnsResponder {
        var announced: Pair<String, Int>? = null
        var closed = false
        val found = MutableStateFlow<List<LanService>>(emptyList())

        override fun advertise(name: String, port: Int, attributes: Map<String, String>) {
            announced = name to port
        }

        override val services: Flow<List<LanService>> = found

        override fun close() {
            closed = true
        }
    }

    private val first = InetAddress.getByName("192.168.0.248")
    private val second = InetAddress.getByName("192.168.0.76")

    private class Machine(var address: InetAddress?) {
        val responders = mutableListOf<FakeResponder>()
        fun discovery(fixed: InetAddress? = null) =
            JmdnsLanDiscovery(fixed, { address }, { FakeResponder(it).also(responders::add) })
    }

    @Test
    fun aNewAddressGetsANewResponderAnnouncingTheSame() = runBlocking {
        val machine = Machine(first)
        val discovery = machine.discovery()
        discovery.advertise("mac", 28461, mapOf("v" to "1"))
        assertEquals(listOf(first), machine.responders.map { it.address })
        // Nothing changed: nothing to announce again
        assertFalse(discovery.refresh())

        machine.address = second
        assertTrue(discovery.refresh())
        val (old, new) = machine.responders
        assertTrue(old.closed)
        assertEquals(second, new.address)
        assertEquals("mac" to 28461, new.announced)
        discovery.close()
        assertTrue(new.closed)
    }

    @Test
    fun theDevicesAreFoundThroughTheResponderOfTheCurrentAddress() = runBlocking {
        val machine = Machine(first)
        val discovery = machine.discovery()
        val phone = LanService("phone", "192.168.0.20", 28461, emptyMap())
        val seen = MutableStateFlow<List<LanService>>(emptyList())
        val collecting = launchCollecting(discovery, seen)
        withTimeout(5_000) { while (machine.responders.isEmpty()) kotlinx.coroutines.delay(10) }
        machine.address = second
        assertTrue(discovery.refresh())
        machine.responders.last().found.value = listOf(phone)
        withTimeout(5_000) { seen.first { it == listOf(phone) } }
        collecting.cancel()
        discovery.close()
    }

    @Test
    fun withoutAnAddressNothingIsAnnouncedUntilOneComes() = runBlocking {
        val machine = Machine(null)
        val discovery = machine.discovery()
        discovery.advertise("mac", 28461, emptyMap())
        assertTrue(machine.responders.isEmpty())
        assertFalse(discovery.refresh())

        machine.address = second
        assertTrue(discovery.refresh())
        assertEquals("mac" to 28461, machine.responders.single().announced)
        discovery.close()
    }

    @Test
    fun anAddressGivenStaysAndAnUnusedDiscoveryStaysIdle() = runBlocking {
        val machine = Machine(first)
        // Not used yet: nothing created, nothing to refresh
        assertFalse(machine.discovery().refresh())
        assertTrue(machine.responders.isEmpty())

        val fixed = machine.discovery(fixed = first)
        fixed.advertise("mac", 1, emptyMap())
        machine.address = second
        assertFalse(fixed.refresh())
        assertEquals(listOf(first), machine.responders.map { it.address })
        fixed.close()
    }

    private fun kotlinx.coroutines.CoroutineScope.launchCollecting(discovery: LanDiscovery, into: MutableStateFlow<List<LanService>>) =
        launch(kotlinx.coroutines.Dispatchers.Default) { discovery.services.collect { into.value = it } }
}
