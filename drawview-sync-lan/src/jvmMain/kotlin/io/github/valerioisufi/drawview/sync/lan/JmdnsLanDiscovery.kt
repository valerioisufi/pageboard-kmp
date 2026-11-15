package io.github.valerioisufi.drawview.sync.lan

import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * [LanDiscovery] of the desktop (JmDNS), on the network of [address] (`null`: the interface of the
 * local network of the default route, see [chooseLocalAddress]).
 */
public class JmdnsLanDiscovery(private val address: InetAddress? = null) : LanDiscovery {
    private val jmdns: JmDNS by lazy { JmDNS.create(address ?: localAddress()) }
    private var announced: ServiceInfo? = null

    override suspend fun advertise(name: String, port: Int, attributes: Map<String, String>): Unit = withContext(Dispatchers.IO) {
        announced?.let { jmdns.unregisterService(it) }
        val info = ServiceInfo.create(TYPE, name, port, 0, 0, attributes)
        jmdns.registerService(info)
        announced = info
    }

    override val services: Flow<List<LanService>> = callbackFlow {
        val found = mutableMapOf<String, LanService>()
        fun publish() = trySend(synchronized(found) { found.values.toList() })
        val listener = object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                // Asked without waiting: serviceResolved follows
                event.dns.requestServiceInfo(event.type, event.name, 1)
            }

            override fun serviceRemoved(event: ServiceEvent) {
                synchronized(found) { found.remove(event.name) }
                publish()
            }

            override fun serviceResolved(event: ServiceEvent) {
                val info = event.info
                val host = info.inet4Addresses.firstOrNull()?.hostAddress ?: return
                val attributes = info.propertyNames.toList().associateWith { info.getPropertyString(it).orEmpty() }
                synchronized(found) { found[event.name] = LanService(event.name, host, info.port, attributes) }
                publish()
            }
        }
        jmdns.addServiceListener(TYPE, listener)
        awaitClose { jmdns.removeServiceListener(TYPE, listener) }
    }.flowOn(Dispatchers.IO)

    override fun close() {
        jmdns.unregisterAllServices()
        jmdns.close()
    }

    private companion object {
        const val TYPE = "${LanDiscovery.SERVICE_TYPE}.local."

        /**
         * The IPv4 address of this machine on the local network: of the interface of the default
         * route, else of the first interface that is not of a virtual machine (see
         * [chooseLocalAddress]).
         */
        fun localAddress(): InetAddress {
            val candidates = NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual && it.supportsMulticast() }
                .flatMap { nic ->
                    nic.inetAddresses.toList()
                        .filter { it is Inet4Address && it.isSiteLocalAddress }
                        .map { LocalInterface(nic.name, nic.displayName.orEmpty(), it) }
                }
            return chooseLocalAddress(candidates, defaultRouteAddress()) ?: InetAddress.getLocalHost()
        }

        /**
         * The local address of the default route, or `null` without one. Connecting a UDP socket
         * sends nothing: it only picks the route (to an address of TEST-NET-1, RFC 5737).
         */
        fun defaultRouteAddress(): InetAddress? = runCatching {
            DatagramSocket().use { socket ->
                socket.connect(InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1)), 9)
                socket.localAddress.takeUnless { it.isAnyLocalAddress }
            }
        }.getOrNull()
    }
}

/** An address of a network interface: its [name], its [displayName] and the [address]. */
internal data class LocalInterface(val name: String, val displayName: String, val address: InetAddress)

/**
 * The address to announce on among [candidates]: the one of the default route ([route]), else the
 * first of an interface that is not of a virtual machine or container (Hyper-V and WSL, VMware,
 * VirtualBox, Docker), else the first. `null` without candidates.
 *
 * The adapters of virtual machines have private addresses and multicast too, and on Windows they
 * often come before the Wi-Fi: announcing there, the devices on the Wi-Fi never meet.
 */
internal fun chooseLocalAddress(candidates: List<LocalInterface>, route: InetAddress?): InetAddress? =
    candidates.firstOrNull { it.address == route }?.address
        ?: candidates.firstOrNull { !it.isOfVirtualMachine() }?.address
        ?: candidates.firstOrNull()?.address

private val VIRTUAL_ADAPTERS = listOf("virtual", "hyper-v", "vethernet", "vmware", "vmnet", "vbox", "wsl", "docker", "virbr", "veth")

private fun LocalInterface.isOfVirtualMachine(): Boolean =
    VIRTUAL_ADAPTERS.any { name.contains(it, ignoreCase = true) || displayName.contains(it, ignoreCase = true) }
