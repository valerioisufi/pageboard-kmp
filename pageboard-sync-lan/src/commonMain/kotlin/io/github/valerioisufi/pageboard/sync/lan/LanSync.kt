package io.github.valerioisufi.pageboard.sync.lan

import io.github.valerioisufi.pageboard.sync.SyncEngine
import io.github.valerioisufi.pageboard.sync.SyncNetwork
import io.github.valerioisufi.pageboard.sync.protocol.DocumentKey
import io.github.valerioisufi.pageboard.sync.protocol.Invitation
import io.github.valerioisufi.pageboard.sync.protocol.SyncException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import io.github.valerioisufi.pageboard.sync.protocol.ExperimentalSyncApi

/**
 * Keeps the shared documents of this device the same as on the devices of the local network
 * (GRASP Controller of working with the others on the same network): it announces the documents
 * this device shares and the invitations it gives (by their tags, see [DocumentKey.tag] and
 * [SyncEngine.announcedTags]), finds the devices sharing the same ones (or the owner of the
 * invitation this device joins with) and connects to them.
 *
 * Every [retryMs] it makes again the connections lost (a device gone and back, a network
 * changed...), listens again if the system closed the listener, and connects to the devices of
 * the invitations joined by their address. Between two devices the one with the smaller
 * [deviceId] (a name unique on the network: the replica of the device, see `SyncEngine.peers`)
 * connects as soon as it finds the other one, the other one when the connection is still missing
 * a moment later.
 *
 * The devices found are remembered for a while after they are gone from the network (their
 * announcements get lost on some networks): one starting again on the same address (see
 * [LanTransport.DEFAULT_PORT]) is connected again even when it is not found again.
 */
@ExperimentalSyncApi
public class LanSync(
    private val engine: SyncEngine,
    private val discovery: LanDiscovery,
    private val scope: CoroutineScope,
    private val deviceId: String,
    private val deviceName: () -> String,
    private val transport: LanTransport = LanTransport(scope),
    private val retryMs: Long = 5_000,
    private val connectTimeoutMs: Long = 5_000
) : SyncNetwork {
    private val mutex = Mutex()

    /** The connections opened by this device (being made or made): to a device, about a document. */
    private val connections = mutableMapOf<Pair<String, String>, Job>()

    /** The devices found, by name, with when they were last found or connected. */
    private val known = mutableMapOf<String, Known>()

    private class Known(val service: LanService, var seen: TimeMark, var found: Boolean)

    /** The addresses of the invitations joined, by document. */
    private val addresses = mutableMapOf<String, String>()
    private var job: Job? = null

    /** The connections failed in a row, by device (or address of an invitation), with the last cause. */
    private val failures = mutableMapOf<String, Failure>()

    private class Failure(val count: Int, val cause: String)

    /** The devices found sharing something with this one, and the addresses of invitations tried, at the last round. */
    private var sharing: Set<String> = emptySet()
    private var joinedAt: Set<String> = emptySet()

    /** The devices found by the discovery last (this one included). */
    private val lastFound = MutableStateFlow<List<LanService>>(emptyList())

    private val devicesState = MutableStateFlow<List<LanDevice>>(emptyList())

    /**
     * The other devices of the network: found (or remembered a while, or joined by the address of
     * an invitation), whether they share something with this device, whether the connections to
     * them work. Updated as they are found and at each round.
     */
    public val devices: StateFlow<List<LanDevice>> = devicesState.asStateFlow()

    /** What keeps the devices from finding each other (see [LanDiscovery.problems]). */
    public val problems: StateFlow<List<DiscoveryProblem>> get() = discovery.problems

    /** Starts listening, announcing and connecting. */
    override suspend fun start() {
        if (job != null) return
        transport.start()
        job = scope.launch {
            launch { transport.incoming.collect { engine.accept(it) } }
            launch {
                discovery.services.collectLatest { found ->
                    lastFound.value = found
                    remember(found)
                    connect(missingOnly = false)
                    // The other device connects first: this one if it has not done it yet
                    delay(SECOND_CONNECTION_MS)
                    connect(missingOnly = true)
                }
            }
            launch {
                while (true) {
                    delay(retryMs)
                    refresh()
                }
            }
            // A document shared, a key or an invitation changed: announced at once
            launch { engine.sharingChanged.collect { announce() } }
        }
        announce()
    }

    /**
     * Makes again what was lost: the listener closed by the system, the announcement too when
     * [networkChanged] (e.g. when the application comes back to the foreground, the system may
     * have stopped it) or when the discovery is on a new address ([LanDiscovery.refresh]), the
     * connections with the devices sharing a document with this one.
     */
    override suspend fun refresh(networkChanged: Boolean) {
        if (job == null) return
        // A new address (the desktop announces where it was otherwise): announced there
        val renewed = discovery.refresh()
        if (!transport.isListening) {
            transport.start()
            announce()
        } else if (networkChanged || renewed) {
            announce()
        }
        connect(missingOnly = true)
    }

    /** Announces the documents shared now (after one is shared or joined). */
    public suspend fun announce() {
        if (job == null) return
        val tags = engine.announcedTags().map { it.take(TAG_LENGTH) }.sorted()
        val attributes = mutableMapOf("v" to PROTOCOL.toString(), "n" to deviceName().take(MAX_NAME_LENGTH))
        // The TXT entries are short: the tags in as many as needed
        tags.chunked(TAGS_PER_ENTRY).forEachIndexed { i, chunk -> attributes["t$i"] = chunk.joinToString(",") }
        // Stopped meanwhile (e.g. a sharing changed while the application goes away): nothing to announce
        val port = try {
            transport.boundPort.takeIf { job != null && transport.isListening } ?: return
        } catch (_: Exception) {
            return
        }
        discovery.advertise(deviceId, port, attributes)
    }

    /**
     * Joins the document of [invitation]: through its owner, at its address if it has one or found
     * on the network.
     */
    override suspend fun join(invitation: Invitation) {
        engine.join(invitation)
        invitation.addresses[LanTransport.NAME]?.let { address -> mutex.withLock { addresses[invitation.documentId] = address } }
        announce()
        connect(missingOnly = true)
    }

    override suspend fun stop() {
        job?.cancel()
        job = null
        mutex.withLock {
            connections.values.forEach { it.cancel() }
            connections.clear()
            failures.clear()
            sharing = emptySet()
            joinedAt = emptySet()
        }
        lastFound.value = emptyList()
        devicesState.value = emptyList()
        engine.stop()
        transport.close()
        discovery.close()
    }

    /**
     * Connects to the devices sharing a document with this one, if not connected yet: when
     * [missingOnly] only where a connection is missing (by either device), otherwise also where
     * this device is the one to connect first.
     */
    private suspend fun connect(missingOnly: Boolean) {
        val keys = engine.connectionKeys()
        // A document of a shared folder is found by the tag of its folder (its own is not announced)
        val announcedBy = keys.map { it.first }.distinct().associateWith { engine.announcedTagOf(it)?.take(TAG_LENGTH) }
        val peers = engine.peers.value
        val (found, invited) = mutex.withLock {
            // Forgotten a while after they are gone (and not connected)
            val connected = peers.mapTo(HashSet()) { it.replica }
            val now = TimeSource.Monotonic.markNow()
            for (device in known.values) if (device.service.name in connected) device.seen = now
            known.values.removeAll { !it.found && it.seen.elapsedNow() > FORGET_AFTER }
            known.values.map { it.service } to addresses.toMap()
        }
        val sharing = mutableSetOf<String>()
        for (service in found) {
            if (service.attributes["v"] != PROTOCOL.toString()) continue
            val tags = service.attributes.filterKeys { it.startsWith("t") }.values.flatMap { it.split(',') }.toSet()
            // The current key of a document first: an older one for a device still on it
            for (documentId in keys.map { it.first }.distinct()) {
                val viaSpace = announcedBy[documentId]?.let { it in tags } == true
                val key = keys.firstOrNull { it.first == documentId && (viaSpace || it.second.tag.take(TAG_LENGTH) in tags) }?.second ?: continue
                sharing += service.name
                // One connection between two devices: the other one makes it first
                if (!missingOnly && deviceId > service.name) continue
                if (peers.any { it.replica == service.name && it.documentId == documentId }) continue
                open(service.name, service.address, documentId, key)
            }
        }
        // The devices of the invitations, while nobody shares the document with this one
        val joinedAt = mutableSetOf<String>()
        for ((documentId, address) in invited) {
            val key = keys.firstOrNull { it.first == documentId }?.second ?: continue
            if (peers.none { it.documentId == documentId }) {
                joinedAt += address
                open(address, address, documentId, key)
            }
        }
        mutex.withLock {
            this.sharing = sharing
            this.joinedAt = joinedAt
        }
        publishDevices()
    }

    /** Publishes [devices], from what the last round found and the outcomes of the connections. */
    private suspend fun publishDevices() {
        val connected = engine.peers.value.mapTo(HashSet()) { it.replica }
        mutex.withLock {
            val found = known.values.map { device ->
                val service = device.service
                val failure = failures[service.name]
                LanDevice(
                    id = service.name,
                    name = service.attributes["n"].orEmpty().take(MAX_NAME_LENGTH),
                    address = service.address,
                    shared = service.name in sharing,
                    otherVersion = service.attributes["v"] != PROTOCOL.toString(),
                    connected = service.name in connected,
                    failures = failure?.count ?: 0,
                    failure = failure?.cause,
                    found = device.found
                )
            }
            val invited = joinedAt.filter { address -> found.none { it.address == address } }.map { address ->
                val failure = failures[address]
                LanDevice(address, "", address, shared = true, otherVersion = false, connected = false, failure?.count ?: 0, failure?.cause, found = false)
            }
            devicesState.value = found + invited
        }
    }

    /**
     * Checks the local network now, within [waitMs]: this device listening, its own announcement
     * seen and answering at its address (the address of a device changed while it announced the
     * old one), a connection to each device found. Nothing travels but the start of a connection,
     * closed at once.
     */
    public suspend fun check(waitMs: Long = CHECK_WAIT_MS): LanCheck {
        if (job == null) return LanCheck(false, null, null, null, emptyList(), discovery.problems.value)
        refresh(networkChanged = true)
        val listening = transport.isListening
        val port = runCatching { transport.boundPort }.getOrNull()
        val self = withTimeoutOrNull(waitMs) { lastFound.first { found -> found.any { it.name == deviceId } } }?.first { it.name == deviceId }
        val selfReachable = self?.let { probe(it.address) == null }
        connect(missingOnly = true)
        val devices = coroutineScope {
            devices.value.map { device ->
                async {
                    val failure = if (device.connected) null else probe(device.address)
                    DeviceCheck(device, failure == null, failure)
                }
            }.awaitAll()
        }
        return LanCheck(listening, port, self?.address, selfReachable, devices, discovery.problems.value)
    }

    /** Opens a connection to [address] and closes it: `null` when it answered, else why not. */
    private suspend fun probe(address: String): String? {
        var failure: String? = TIMEOUT
        withTimeoutOrNull(connectTimeoutMs) {
            failure = try {
                transport.connect(address).close()
                null
            } catch (e: SyncException) {
                causeOf(e)
            }
        }
        return failure
    }

    /** Keeps the outcome of a connection to [peer]: failed because of [cause], or made (`null`). */
    private suspend fun noteConnection(peer: String, cause: String?) {
        mutex.withLock {
            if (cause == null) failures.remove(peer) else failures[peer] = Failure((failures[peer]?.count ?: 0) + 1, cause)
        }
        publishDevices()
    }

    /** Remembers the devices [found] on the network (the ones not found any more for a while). */
    private suspend fun remember(found: List<LanService>) = mutex.withLock {
        val now = TimeSource.Monotonic.markNow()
        for (device in known.values) device.found = false
        for (service in found) if (service.name != deviceId) known[service.name] = Known(service, now, found = true)
    }

    /** Connects to the device [peer] at [address] about the document [documentId], unless connecting already. */
    private suspend fun open(peer: String, address: String, documentId: String, key: DocumentKey) = mutex.withLock {
        val id = peer to documentId
        if (connections[id]?.isActive == true) return@withLock
        connections[id] = scope.launch {
            var cause = TIMEOUT
            val channel = withTimeoutOrNull(connectTimeoutMs) {
                try {
                    transport.connect(address)
                } catch (e: SyncException) {
                    cause = causeOf(e)
                    null
                }
            }
            noteConnection(peer, if (channel == null) cause else null)
            if (channel == null) return@launch
            // As long as the connection lasts
            engine.connect(channel, documentId, key).join()
        }
    }

    private companion object {
        const val PROTOCOL = 1
        const val SECOND_CONNECTION_MS = 1_000L
        val FORGET_AFTER = 10.minutes
        const val TAG_LENGTH = 12
        const val TAGS_PER_ENTRY = 19
        const val MAX_NAME_LENGTH = 60
        const val CHECK_WAIT_MS = 5_000L
        const val TIMEOUT = "timeout"

        /** Why a connection failed, for the details: the exception of the system under [e], else its message. */
        fun causeOf(e: SyncException): String {
            val cause = e.cause ?: return e.message ?: "SyncException"
            val type = cause::class.simpleName ?: "Exception"
            return cause.message?.let { "$type: ${it.take(MAX_CAUSE_LENGTH)}" } ?: type
        }

        const val MAX_CAUSE_LENGTH = 200
    }
}
