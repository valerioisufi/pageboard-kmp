package io.github.valerioisufi.drawview.sync.lan

import io.github.valerioisufi.drawview.sync.SyncEngine
import io.github.valerioisufi.drawview.sync.SyncNetwork
import io.github.valerioisufi.drawview.sync.protocol.DocumentKey
import io.github.valerioisufi.drawview.sync.protocol.Invitation
import io.github.valerioisufi.drawview.sync.protocol.SyncException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

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

    /** Starts listening, announcing and connecting. */
    override suspend fun start() {
        if (job != null) return
        transport.start()
        job = scope.launch {
            launch { transport.incoming.collect { engine.accept(it) } }
            launch {
                discovery.services.collectLatest { found ->
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
     * have stopped it), the connections with the devices sharing a document with this one.
     */
    override suspend fun refresh(networkChanged: Boolean) {
        if (job == null) return
        if (!transport.isListening) {
            transport.start()
            announce()
        } else if (networkChanged) {
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
        }
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
        for (service in found) {
            if (service.attributes["v"] != PROTOCOL.toString()) continue
            // One connection between two devices: the other one makes it first
            if (!missingOnly && deviceId > service.name) continue
            val tags = service.attributes.filterKeys { it.startsWith("t") }.values.flatMap { it.split(',') }.toSet()
            // The current key of a document first: an older one for a device still on it
            for (documentId in keys.map { it.first }.distinct()) {
                val viaSpace = announcedBy[documentId]?.let { it in tags } == true
                val key = keys.firstOrNull { it.first == documentId && (viaSpace || it.second.tag.take(TAG_LENGTH) in tags) }?.second ?: continue
                if (peers.any { it.replica == service.name && it.documentId == documentId }) continue
                open(service.name, service.address, documentId, key)
            }
        }
        // The devices of the invitations, while nobody shares the document with this one
        for ((documentId, address) in invited) {
            val key = keys.firstOrNull { it.first == documentId }?.second ?: continue
            if (peers.none { it.documentId == documentId }) open(address, address, documentId, key)
        }
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
            val channel = withTimeoutOrNull(connectTimeoutMs) {
                try {
                    transport.connect(address)
                } catch (_: SyncException) {
                    null
                }
            } ?: return@launch
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
    }
}
