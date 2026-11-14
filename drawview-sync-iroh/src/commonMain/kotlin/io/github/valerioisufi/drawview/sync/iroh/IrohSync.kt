package io.github.valerioisufi.drawview.sync.iroh

import io.github.valerioisufi.drawview.sync.SyncEngine
import io.github.valerioisufi.drawview.sync.SyncNetwork
import io.github.valerioisufi.drawview.sync.protocol.DocumentKey
import io.github.valerioisufi.drawview.sync.protocol.Invitation
import io.github.valerioisufi.drawview.sync.protocol.SyncException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the shared documents of this device the same as on the other members over the internet
 * (GRASP Controller of working with the others wherever they are): it starts the iroh [transport],
 * says where this device is ([SyncEngine.publishAddress]), serves the channels the others open,
 * and connects to the members of the shared documents not connected yet, at the addresses they
 * gave ([SyncEngine.addresses]), and to the owner of the invitations joined, at the address of
 * the invitation.
 *
 * Between two members, the one with the smaller identity ([SyncEngine.device]) connects as soon
 * as it can, the other one when the connection is still missing at the next round. Every
 * [retryMs] it makes again the connections lost; [refresh] does it at once (after the background,
 * a change of network). A member connected through another transport (the local network) is not
 * connected to again: the first round waits [firstDelayMs], so that the local network goes first.
 */
public class IrohSync(
    private val engine: SyncEngine,
    private val transport: IrohTransport,
    private val scope: CoroutineScope,
    private val retryMs: Long = 10_000,
    private val firstDelayMs: Long = 3_000,
    private val connectTimeoutMs: Long = 15_000
) : SyncNetwork {
    private val mutex = Mutex()

    /** The connections opened by this device (being made or made): to a device, about a document. */
    private val connections = mutableMapOf<Pair<String, String>, Job>()

    /** The address of the owner of each invitation joined, by document. */
    private val owners = mutableMapOf<String, String>()
    private var job: Job? = null

    /** Starts the transport, says where this device is, serves the others and connects to them. */
    override suspend fun start() {
        if (job != null) return
        transport.start()
        publish()
        @OptIn(FlowPreview::class)
        job = scope.launch {
            launch { transport.incoming.collect { engine.accept(it) } }
            launch {
                delay(firstDelayMs)
                connect(missingOnly = false)
                while (true) {
                    delay(retryMs)
                    // Its relay reached late, or another one: the others are told
                    publish()
                    connect(missingOnly = true)
                }
            }
            // A member arrived or moved, a document shared or joined: at once
            launch {
                merge(engine.addresses.drop(1), engine.memberships.drop(1), engine.spaceMemberships.drop(1), engine.sharingChanged)
                    .debounce(DEBOUNCE_MS)
                    .collect { connect(missingOnly = false) }
            }
        }
    }

    /**
     * Joins the document of [invitation]: through its owner, at its address on iroh (if the
     * invitation has one).
     */
    override suspend fun join(invitation: Invitation) {
        engine.join(invitation)
        invitation.addresses[IrohTransport.NAME]?.let { address -> mutex.withLock { owners[invitation.documentId] = address } }
        if (job != null) connect(missingOnly = true)
    }

    /**
     * Makes again what was lost: tells the transport that the network changed when
     * [networkChanged], says where this device is now, connects to the members missing.
     */
    override suspend fun refresh(networkChanged: Boolean) {
        if (job == null) return
        if (networkChanged) transport.networkChanged()
        publish()
        connect(missingOnly = true)
    }

    /** Stops: the connections, the transport, and this device no longer says where it is. */
    override suspend fun stop() {
        job?.cancel()
        job = null
        mutex.withLock {
            connections.values.forEach { it.cancel() }
            connections.clear()
        }
        engine.publishAddress(IrohTransport.NAME, null)
        transport.close()
    }

    /**
     * Says where this device is: its identifier and its relays, which do not change with the
     * network (the direct addresses are found through the relays); everything without relays.
     */
    private suspend fun publish() {
        val address = transport.currentAddress() ?: return
        val parts = address.split(' ')
        val relays = parts.drop(1).filter { "://" in it }
        engine.publishAddress(IrohTransport.NAME, if (relays.isEmpty()) address else (parts.take(1) + relays).joinToString(" "))
    }

    /**
     * Connects to the members of the shared documents not connected, and to the owners of the
     * invitations joined: when [missingOnly], wherever a connection is missing; otherwise also only
     * where this device is the one to connect first.
     */
    private suspend fun connect(missingOnly: Boolean) {
        val me = engine.device
        val keys = engine.connectionKeys()
        val connected = engine.peers.value.mapTo(HashSet()) { it.documentId to it.device }
        val connectedDocuments = engine.peers.value.mapTo(HashSet()) { it.documentId }
        // Of the documents and of the shared folders (their connections are made as the documents')
        val memberships = engine.memberships.value + engine.spaceMemberships.value
        val addresses = engine.addresses.value
        val owners = mutex.withLock { owners.toMap() }
        for (documentId in keys.map { it.first }.distinct()) {
            // The current key of the document first (an older one is for a device still on it)
            val key = keys.first { it.first == documentId }.second
            val membership = memberships[documentId]
            if (membership == null) {
                // Joining: through the owner only
                val owner = owners[documentId] ?: continue
                if (documentId !in connectedDocuments) open(owner.substringBefore(' '), owner, documentId, key)
                continue
            }
            if (membership.ended) continue
            for (member in membership.members) {
                val device = member.device
                if (device == me || (documentId to device) in connected) continue
                if (!missingOnly && device < me) continue
                val address = addresses[device]?.get(IrohTransport.NAME)?.address ?: continue
                open(device, address, documentId, key)
            }
        }
    }

    /** Connects to [peer] at [address] about [documentId], unless connecting to it already. */
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
        const val DEBOUNCE_MS = 200L
    }
}
