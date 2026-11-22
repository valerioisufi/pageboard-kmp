package io.github.valerioisufi.pageboard.sync.lan

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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [LanDiscovery] of the desktop (JmDNS), on the network of [address] (`null`: the interface of the
 * local network of the default route, see [chooseLocalAddress], followed as it changes).
 *
 * JmDNS answers on the address it was created on: when the address of this machine changes (a new
 * lease, another Wi-Fi, a cable), [refresh] creates it again on the new one, else the others would
 * find this device where it is no more. Without an address on a local network nothing is
 * announced (not the loopback, where nobody would reach it).
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class JmdnsLanDiscovery internal constructor(
    private val address: InetAddress?,
    private val localAddress: () -> InetAddress?,
    private val createResponder: (InetAddress) -> MdnsResponder
) : LanDiscovery {
    @JvmOverloads
    public constructor(address: InetAddress? = null) : this(address, ::localNetworkAddress, ::JmdnsResponder)

    /** The responder on the current address, `null` before the first use or without an address. */
    private val responder = MutableStateFlow<MdnsResponder?>(null)
    private val lock = Mutex()
    @Volatile private var started = false
    @Volatile private var bound: InetAddress? = null
    @Volatile private var announced: Announcement? = null

    private class Announcement(val name: String, val port: Int, val attributes: Map<String, String>)

    override suspend fun advertise(name: String, port: Int, attributes: Map<String, String>): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            announced = Announcement(name, port, attributes)
            startLocked()?.advertise(name, port, attributes)
        }
    }

    override val services: Flow<List<LanService>> = flow {
        lock.withLock { startLocked() }
        emitAll(responder.flatMapLatest { it?.services ?: flowOf(emptyList()) })
    }.flowOn(Dispatchers.IO)

    override suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!started || address != null) return@withLock false
            val now = localAddress()
            if (now == bound) return@withLock false
            responder.value?.close()
            responder.value = null
            bound = now
            responder.value = now?.let(::createOn)
            // Announced again by the caller ([LanSync]), and here for whoever does not
            announced?.let { responder.value?.advertise(it.name, it.port, it.attributes) }
            true
        }
    }

    override fun close() {
        // Not waiting for an announcement or a refresh being made: closing is never blocked
        val current = responder.getAndUpdate { null }
        started = false
        bound = null
        announced = null
        current?.close()
    }

    /** The responder, created on the first use; `null` without an address. Called holding [lock]. */
    private fun startLocked(): MdnsResponder? {
        if (!started) {
            started = true
            bound = address ?: localAddress()
            responder.value = bound?.let(::createOn)
        }
        return responder.value
    }

    private fun createOn(address: InetAddress): MdnsResponder? = try {
        createResponder(address)
    } catch (_: Exception) {
        null
    }
}

/** Answers and asks for the services of this device on one address (JmDNS, or a fake in the tests). */
internal interface MdnsResponder {
    /** Announces this device, in place of the previous announcement. */
    fun advertise(name: String, port: Int, attributes: Map<String, String>)

    /** The devices found, while collected. */
    val services: Flow<List<LanService>>

    fun close()
}

/** [MdnsResponder] of JmDNS, created on [address]. */
internal class JmdnsResponder(address: InetAddress) : MdnsResponder {
    private val jmdns: JmDNS = JmDNS.create(address)
    private var announced: ServiceInfo? = null

    override fun advertise(name: String, port: Int, attributes: Map<String, String>) {
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
    }

    override fun close() {
        jmdns.unregisterAllServices()
        jmdns.close()
    }

    private companion object {
        const val TYPE = "${LanDiscovery.SERVICE_TYPE}.local."
    }
}

/**
 * The IPv4 address of this machine on the local network: of the interface of the default route,
 * else of the first interface that is not of a virtual machine (see [chooseLocalAddress]); `null`
 * without one.
 */
internal fun localNetworkAddress(): InetAddress? {
    val candidates = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList())
        .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual && it.supportsMulticast() }.getOrDefault(false) }
        .flatMap { nic ->
            nic.inetAddresses.toList()
                .filter { it is Inet4Address && it.isSiteLocalAddress }
                .map { LocalInterface(nic.name, nic.displayName.orEmpty(), it) }
        }
    return chooseLocalAddress(candidates, defaultRouteAddress())
}

/**
 * The local address of the default route, or `null` without one. Connecting a UDP socket sends
 * nothing: it only picks the route (to an address of TEST-NET-1, RFC 5737).
 */
private fun defaultRouteAddress(): InetAddress? = runCatching {
    DatagramSocket().use { socket ->
        socket.connect(InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1)), 9)
        socket.localAddress.takeUnless { it.isAnyLocalAddress }
    }
}.getOrNull()

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
