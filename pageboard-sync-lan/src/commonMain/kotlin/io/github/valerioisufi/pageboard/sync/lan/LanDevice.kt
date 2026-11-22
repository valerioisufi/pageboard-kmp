package io.github.valerioisufi.pageboard.sync.lan

/**
 * A device of the local network as this one sees it (see [LanSync.devices]): what it announces,
 * whether it shares something with this device, whether the connections to it work.
 *
 * What it announces comes from the other device and is not trusted: its [name] is only shown,
 * bounded.
 */
public data class LanDevice(
    /** Its name on the network: the replica of the device. */
    val id: String,
    /** The name it shows (blank if it gave none). */
    val name: String,
    /** Where it is reached (`host:port`). */
    val address: String,
    /** Whether it announces a document or a shared folder this device shares too. */
    val shared: Boolean,
    /** Whether it is on another version of the protocol (they cannot sync). */
    val otherVersion: Boolean,
    /** Whether it is connected to this device now (about some document). */
    val connected: Boolean,
    /** The connections to it failed in a row, 0 once one works. */
    val failures: Int = 0,
    /** Why the last connection failed (for the details), `null` when none failed. */
    val failure: String? = null,
    /** Whether it was found on the network now, rather than remembered or joined by its address. */
    val found: Boolean = true
)

/**
 * The result of [LanSync.check]: what works on the local network and what does not, for this
 * device and for the others.
 */
public data class LanCheck(
    /** Whether this device listens for the others, and on which port. */
    val listening: Boolean,
    val port: Int?,
    /** The address the others find this device at, `null` when its own announcement was not seen. */
    val announcedAt: String?,
    /** Whether this device answers at that address (`null` when not seen). */
    val announcedReachable: Boolean?,
    /** The other devices found, each with the result of a connection to it. */
    val devices: List<DeviceCheck>,
    /** What the discovery reports (see [LanDiscovery.problems]). */
    val problems: List<DiscoveryProblem>
)

/** A device of [LanCheck]: whether it answered, else why. */
public data class DeviceCheck(val device: LanDevice, val reachable: Boolean, val failure: String?)
