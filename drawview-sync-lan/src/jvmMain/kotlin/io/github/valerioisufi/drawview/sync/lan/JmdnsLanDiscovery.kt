package io.github.valerioisufi.drawview.sync.lan

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
 * [LanDiscovery] of the desktop (JmDNS), on the network of [address] (`null`: the first network
 * interface of the local network).
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

        /** The IPv4 address of the first interface of the local network (up, with multicast). */
        fun localAddress(): InetAddress = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual && it.supportsMulticast() }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?: InetAddress.getLocalHost()
    }
}
