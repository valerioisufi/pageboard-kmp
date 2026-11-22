package io.github.valerioisufi.pageboard.sync.lan

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** A device announcing itself on the local network: its name, its address and its [attributes]. */
public data class LanService(val name: String, val host: String, val port: Int, val attributes: Map<String, String>) {
    /** Its address for [LanTransport.connect]. */
    val address: String get() = if (':' in host) "[$host]:$port" else "$host:$port"
}

/**
 * Announces this device on the local network and finds the others (DNS-SD over multicast DNS,
 * service [SERVICE_TYPE]): JmDNS on the desktop, `NsdManager` on Android, Bonjour on iOS.
 */
public interface LanDiscovery {
    /**
     * Announces this device as [name], listening on [port], with [attributes] (the TXT record:
     * short values); replaces the previous announcement.
     */
    public suspend fun advertise(name: String, port: Int, attributes: Map<String, String>)

    /** The devices found (this one included), updated as they come and go, while collected. */
    public val services: Flow<List<LanService>>

    /**
     * Takes the network as it is now (an address of this device changed, a network came or went):
     * `true` when this device must be announced again ([advertise]), as on a new network.
     */
    public suspend fun refresh(): Boolean = false

    /**
     * What keeps the devices from finding each other now (a permission refused, no local network,
     * an announcement failed...), empty when nothing is known to be wrong.
     */
    public val problems: StateFlow<List<DiscoveryProblem>> get() = DiscoveryProblems.NONE

    public fun close()

    public companion object {
        internal const val SERVICE_TYPE = "_pageboard._tcp"
    }
}
