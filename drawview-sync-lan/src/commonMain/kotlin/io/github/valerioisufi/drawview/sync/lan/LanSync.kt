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

/**
 * Keeps the shared documents of this device the same as on the devices of the local network
 * (GRASP Controller of working with the others on the same network): it announces the documents
 * this device shares (by their tags, see [DocumentKey.tag]), finds the devices sharing the same
 * ones and connects to them; the connections lost are made again while the devices are there.
 *
 * Between two devices the one with the smaller [deviceId] (a name unique on the network, e.g. the
 * replica of the device) opens the connection.
 */
class LanSync(
    private val engine: SyncEngine,
    private val shared: SharedDocuments,
    private val discovery: LanDiscovery,
    private val scope: CoroutineScope,
    private val deviceId: String,
    private val deviceName: String,
    private val transport: LanTransport = LanTransport(scope)
) {
    private val mutex = Mutex()

    /** The connections opened by this device: to a device or an address, about a document. */
    private val connections = mutableMapOf<Pair<String, String>, Job>()
    private var services: List<LanService> = emptyList()
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
                    connect()
                }
            }
            launch {
                while (true) {
                    delay(RETRY_DELAY_MS)
                    connect()
                }
            }
        }
        announce()
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
        announce()
        invitation.address?.let { address -> open(address, address, invitation.documentId, invitation.key) }
        connect()
    }

    suspend fun stop() {
        job?.cancel()
        job = null
        engine.stop()
        transport.close()
        discovery.close()
    }

    /** Connects to the devices sharing a document with this one, if not connected yet. */
    private suspend fun connect() {
        val keys = shared.all()
        val found = mutex.withLock { services }
        for (service in found) {
            // One connection between two devices: the other one opens it
            if (deviceId > service.name || service.attributes["v"] != PROTOCOL.toString()) continue
            val tags = service.attributes.filterKeys { it.startsWith("t") }.values.flatMap { it.split(',') }.toSet()
            for ((documentId, key) in keys) {
                if (key.tag.take(TAG_LENGTH) in tags) open(service.name, service.address, documentId, key)
            }
        }
    }

    private suspend fun open(peer: String, address: String, documentId: String, key: DocumentKey) {
        val id = peer to documentId
        if (mutex.withLock { connections[id]?.isActive == true }) return
        val channel = try {
            transport.connect(address)
        } catch (_: SyncException) {
            return
        }
        val connection = engine.join(channel, Invitation(documentId, "", key))
        mutex.withLock { connections[id] = connection }
    }

    private companion object {
        const val PROTOCOL = 1
        const val TAG_LENGTH = 12
        const val TAGS_PER_ENTRY = 19
        const val MAX_NAME_LENGTH = 60
        const val RETRY_DELAY_MS = 10_000L
    }
}
