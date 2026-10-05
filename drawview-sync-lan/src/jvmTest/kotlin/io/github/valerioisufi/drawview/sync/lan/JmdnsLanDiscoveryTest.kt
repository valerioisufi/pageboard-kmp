package io.github.valerioisufi.drawview.sync.lan

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multicast DNS on the network of the machine: run with `DRAWVIEW_MDNS_TESTS=1`. It needs a
 * network interface with multicast (e.g. Wi-Fi) and, on macOS, the permission of the process to
 * use the local network (System Settings > Privacy & Security > Local Network).
 */
class JmdnsLanDiscoveryTest {

    @Test
    fun twoDevicesFindEachOther() {
        if (System.getenv("DRAWVIEW_MDNS_TESTS") != "1") return
        runBlocking(Dispatchers.IO) {
            val one = JmdnsLanDiscovery()
            val other = JmdnsLanDiscovery()
            try {
                one.advertise("drawview-test-one", 40001, mapOf("v" to "1", "t0" to "abc,def"))
                other.advertise("drawview-test-other", 40002, mapOf("v" to "1"))
                val seen = withTimeout(20_000) { one.services.first { services -> services.any { it.name == "drawview-test-other" } } }
                assertEquals(40002, seen.single { it.name == "drawview-test-other" }.port)
                val found = withTimeout(20_000) { other.services.first { services -> services.any { it.name == "drawview-test-one" } } }
                assertEquals("abc,def", found.single { it.name == "drawview-test-one" }.attributes["t0"])
            } finally {
                one.close()
                other.close()
            }
        }
    }
}
