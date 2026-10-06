package io.github.valerioisufi.drawview.sync.lan

import io.github.valerioisufi.drawview.sync.DocumentKey
import io.github.valerioisufi.drawview.sync.Invitation
import io.github.valerioisufi.drawview.sync.SharedDocuments
import io.github.valerioisufi.drawview.sync.SyncEngine
import io.github.valerioisufi.drawview.sync.SyncException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the shared documents of this device the same as on the devices of the local network
 * (GRASP Controller of working with the others on the same network): it announces the documents
 * this device shares (by their tags, see [DocumentKey.tag]), finds the devices sharing the same
 * ones and connects to them.
 *
 * Every [retryMs] it makes again the connections lost (a device gone and back, a network
 * changed...), listens again if the system closed the listener, and connects to the devices of
 * the invitations joined by their address. Between two devices the one with the smaller
 * [deviceId] (a name unique on the network: the replica of the device, see `SyncEngine.peers`)
 * connects as soon as it finds the other one, the other one only when the connection is still
 * missing at the next retry.
 */
class LanSync(
    private val engine: SyncEngine,
    private val shared: SharedDocuments,
    private val discovery: LanDiscovery,
    private val scope: CoroutineScope,
    private val deviceId: String,
    private val deviceName: String,
    private val transport: LanTransport = LanTransport(scope),
    private val retryMs: Long = 5_000,
    private val connectTimeoutMs: Long = 5_000
) {
    private val mutex = Mutex()

    /** The connections opened by this device (being made or made): to a device, about a document. */
    private val connections = mutableMapOf<Pair<String, String>, Job>()
    private var services: List<LanService> = emptyList()

    /** The addresses of the invitations joined, by document. */
    private val addresses = mutableMapOf<String, String>()
    private var job: Job? = null

    /** Starts listening, announcing and connecting. */
    suspend fun start() {
        if (job != null) return
        transport.start()
        job = scope.launch {
            launch { transport.incoming.collect { engine.accept(it) } }
            launch {
                discovery.services.collect { found ->
                    mutex.withLock { services = found.filter { it.name != deviceId } }
                    connect(missingOnly = false)
                }
            }
            launch {
                while (true) {
                    delay(retryMs)
                    refresh()
                }
            }
        }
        announce()
    }

    /**
     * Makes again what was lost: the listener closed by the system, the announcement too when
     * [announceAgain] (e.g. when the application comes back to the foreground, the system may
     * have stopped it), the connections with the devices sharing a document with this one.
     */
    suspend fun refresh(announceAgain: Boolean = false) {
        if (job == null) return
        if (!transport.isListening) {
            transport.start()
            announce()
        } else if (announceAgain) {
            announce()
        }
        connect(missingOnly = true)
    }

    /** Announces the documents shared now (after one is shared or joined). */
    suspend fun announce() {
        if (job == null) return
        val tags = shared.all().values.map { it.tag.take(TAG_LENGTH) }.sorted()
        val attributes = mutableMapOf("v" to PROTOCOL.toString(), "n" to deviceName.take(MAX_NAME_LENGTH))
        // The TXT entries are short: the tags in as many as needed
        tags.chunked(TAGS_PER_ENTRY).forEachIndexed { i, chunk -> attributes["t$i"] = chunk.joinToString(",") }
        discovery.advertise(deviceId, transport.boundPort, attributes)
    }

    /**
     * Joins the document of [invitation]: through the device at its address if it has one, and
     * through the devices sharing it on the network.
     */
    suspend fun join(invitation: Invitation) {
        shared.add(invitation.documentId, invitation.key)
        invitation.address?.let { address -> mutex.withLock { addresses[invitation.documentId] = address } }
        announce()
        connect(missingOnly = true)
    }

    suspend fun stop() {
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
        val keys = shared.all()
        val peers = engine.peers.value
        val (found, invited) = mutex.withLock { services to addresses.toMap() }
        for (service in found) {
            if (service.attributes["v"] != PROTOCOL.toString()) continue
            // One connection between two devices: the other one makes it first
            if (!missingOnly && deviceId > service.name) continue
            val tags = service.attributes.filterKeys { it.startsWith("t") }.values.flatMap { it.split(',') }.toSet()
            for ((documentId, key) in keys) {
                if (key.tag.take(TAG_LENGTH) !in tags) continue
                if (peers.any { it.replica == service.name && it.documentId == documentId }) continue
                open(service.name, service.address, documentId, key)
            }
        }
        // The devices of the invitations, while nobody shares the document with this one
        for ((documentId, address) in invited) {
            val key = keys[documentId] ?: continue
            if (peers.none { it.documentId == documentId }) open(address, address, documentId, key)
        }
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
            engine.join(channel, Invitation(documentId, "", key)).join()
        }
    }

    private companion object {
        const val PROTOCOL = 1
        const val TAG_LENGTH = 12
        const val TAGS_PER_ENTRY = 19
        const val MAX_NAME_LENGTH = 60
    }
}
