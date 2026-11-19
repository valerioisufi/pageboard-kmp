package io.github.valerioisufi.pageboard.sync

import io.github.valerioisufi.pageboard.sync.protocol.DeviceAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The addresses of the other devices this one knows (GRASP Information Expert of which address
 * to keep), by device and transport: signed by their device, of a member, the most recent of each
 * device and transport.
 */
internal class KnownAddresses {
    private val state = MutableStateFlow<Map<String, Map<String, DeviceAddress>>>(emptyMap())

    /** The addresses, by device and transport. */
    val all: StateFlow<Map<String, Map<String, DeviceAddress>>> = state.asStateFlow()

    /**
     * Keeps the addresses of [received] signed by their device, of a member ([isMember]), not of
     * this device ([self]) and more recent than the one known of their device and transport: the
     * ones kept, which the devices that do not have them yet may want.
     */
    fun take(received: List<DeviceAddress>, self: String, isMember: (String) -> Boolean): List<DeviceAddress> {
        val wanted = received.filter { it.device != self && isMember(it.device) }
        if (wanted.isEmpty()) return emptyList()
        // Checked once, outside of the update (which may run again)
        val signed = wanted.filter { it.isSigned() }
        var taken: List<DeviceAddress> = emptyList()
        state.update { known ->
            val accepted = mutableListOf<DeviceAddress>()
            val next = known.toMutableMap()
            for (address in signed) {
                val ofDevice = next[address.device].orEmpty()
                if (!address.isNewerThan(ofDevice[address.transport])) continue
                next[address.device] = ofDevice + (address.transport to address)
                accepted += address
            }
            taken = accepted
            next
        }
        return taken
    }

    /** Keeps the addresses of the devices [keep] says to only: whether some were forgotten. */
    fun retain(keep: (String) -> Boolean): Boolean {
        var forgot = false
        state.update { known ->
            val kept = known.filterKeys(keep)
            forgot = kept.size != known.size
            kept
        }
        return forgot
    }

    /** Every address known. */
    fun list(): List<DeviceAddress> = state.value.values.flatMap { it.values }

    /** The addresses of the devices [devices]. */
    fun of(devices: Collection<String>): List<DeviceAddress> = devices.flatMap { state.value[it]?.values.orEmpty() }
}
