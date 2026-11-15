package io.github.valerioisufi.drawview.sync.lan

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The choice of the address the desktop announces on ([chooseLocalAddress]). */
class LocalAddressTest {

    private val hyperV = LocalInterface("eth3", "Hyper-V Virtual Ethernet Adapter", address("172.21.16.1"))
    private val vmware = LocalInterface("eth5", "VMware Virtual Ethernet Adapter for VMnet8", address("192.168.78.1"))
    private val wifi = LocalInterface("wlan1", "Intel(R) Wi-Fi 6E AX211 160MHz", address("192.168.0.3"))

    @Test
    fun theInterfaceOfTheDefaultRouteIsChosen() {
        assertEquals(wifi.address, chooseLocalAddress(listOf(hyperV, vmware, wifi), route = wifi.address))
    }

    @Test
    fun withoutARouteTheAdaptersOfVirtualMachinesComeLast() {
        assertEquals(wifi.address, chooseLocalAddress(listOf(hyperV, vmware, wifi), route = null))
    }

    @Test
    fun aRouteThroughAnotherInterfaceIsIgnored() {
        assertEquals(wifi.address, chooseLocalAddress(listOf(hyperV, wifi), route = address("10.8.0.2")))
    }

    @Test
    fun onlyVirtualAdaptersGiveTheFirst() {
        assertEquals(hyperV.address, chooseLocalAddress(listOf(hyperV, vmware), route = null))
    }

    @Test
    fun noCandidatesGiveNothing() {
        assertNull(chooseLocalAddress(emptyList(), route = null))
    }

    private fun address(text: String): InetAddress = InetAddress.getByName(text)
}
