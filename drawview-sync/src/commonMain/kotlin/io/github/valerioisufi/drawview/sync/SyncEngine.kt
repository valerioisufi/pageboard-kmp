package io.github.valerioisufi.drawview.sync

import io.github.valerioisufi.drawview.core.change.ChangeObserver
import io.github.valerioisufi.drawview.core.change.DocumentSession
import io.github.valerioisufi.drawview.core.change.DocumentSnapshot
import io.github.valerioisufi.drawview.core.change.Operation
import io.github.valerioisufi.drawview.core.change.snapshot
import io.github.valerioisufi.drawview.core.util.currentTimeMillis
import io.github.valerioisufi.drawview.data.OpenDocument
import io.github.valerioisufi.drawview.data.OpenDocuments
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.min

/** A device working on the document [documentId] with this one. */
data class Peer(val documentId: String, val replica: String, val name: String)

/**
 * Keeps the shared documents of this device the same as on the other devices (GRASP Controller
 * of working on a document with others): over every connection ([accept], [join]) the two
 * devices send each other the operations the other misses, then the operations as they are made
 * (the ones of the other devices too: a device relays them), and the files of the resources.
 *
 * The documents are changed through their [OpenDocuments] session, open in an editor or not;
 * the connections come from any [SyncTransport] and are encrypted with the key of their document
 * ([SecureChannel]). The documents received from the other devices are stored in the folder
 * [receivedFolder] gives.
 *
 * [scope] runs the connections (a supervisor scope: a connection failing does not stop the
 * others).
 */
class SyncEngine(
    private val documents: OpenDocuments,
    private val shared: SharedDocuments,
    private val scope: CoroutineScope,
    private val deviceName: String,
    private val files: ResourceFiles = PlatformResourceFiles,
    private val receivedFolder: suspend () -> String? = { null }
) {
    private val mutex = Mutex()
    private val syncs = mutableMapOf<String, DocumentSync>()
    private val links = mutableSetOf<Link>()
    private val connected = MutableStateFlow<List<Peer>>(emptyList())

    /** The devices connected, with the document each one works on. */
    val peers: StateFlow<List<Peer>> = connected.asStateFlow()

    /** The last presence of every device on every document, and the connection it came from. */
    private val lastPresences = mutableMapOf<Pair<String, String>, Pair<Presence, Link>>()
    private val presenceState = MutableStateFlow<List<Presence>>(emptyList())
    private var presenceSequence = 0L

    /**
     * Where the other devices are on the documents (see [Presence]); a device that stops sending
     * its presence for a while is gone (see [Presence.receivedAt]).
     */
    val presences: StateFlow<List<Presence>> = presenceState.asStateFlow()

    /** Tells the devices working on the document of [presence] where this one is. */
    suspend fun updatePresence(presence: Presence) {
        val replica = documents.replicaId()
        val targets = mutex.withLock {
            presenceSequence = maxOf(presenceSequence + 1, currentTimeMillis())
            links.filter { it.documentId == presence.documentId && it.peerHello != null }
        }
        if (targets.isEmpty()) return
        val message = PresenceMessage(presence.copy(replica = replica, name = deviceName, sequence = presenceSequence))
        targets.forEach { it.outbox.trySend(message) }
    }

    /**
     * Shares the document [documentId] (titled [title]): the invitation to give to the devices
     * that may join it, reaching this one at [address].
     */
    suspend fun share(documentId: String, title: String, address: String? = null): Invitation {
        val key = shared.keyOf(documentId) ?: DocumentKey.generate().also { shared.add(documentId, it) }
        return Invitation(documentId, title, key, address)
    }

    /** Stops sharing the document [documentId]: its connections are closed. */
    suspend fun unshare(documentId: String) {
        shared.remove(documentId)
        mutex.withLock { links.filter { it.documentId == documentId } }.forEach { it.channel.close() }
    }

    /** Serves a channel another device opened to this one (see [SyncTransport.incoming]). */
    fun accept(channel: SyncChannel): Job = scope.launch {
        try {
            val open = channel.receive()?.let(SyncCodec::decodeOpen) ?: return@launch
            if (open.protocol != PROTOCOL_VERSION) return@launch
            val (documentId, key) = shared.all().entries.firstOrNull { it.value.tag == open.tag } ?: return@launch
            val secure = SecureChannel.of(channel, key)
            // Without the document yet (joined through an invitation): it comes with the first messages
            val sync = acquire(documentId)
            run(Link(secure, documentId, key), sync)
        } catch (_: SyncException) {
            // Refused or lost before it started
        } finally {
            channel.close()
        }
    }

    /**
     * Joins the document of [invitation] through [channel] (opened to a device sharing it): the
     * document is received if this device does not have it, then kept the same as long as the
     * channel stays open.
     */
    fun join(channel: SyncChannel, invitation: Invitation): Job = scope.launch {
        try {
            shared.add(invitation.documentId, invitation.key)
            channel.send(SyncCodec.encodeOpen(OpenFrame(PROTOCOL_VERSION, invitation.key.tag)))
            val secure = SecureChannel.of(channel, invitation.key)
            // Without the document yet: it comes with the first messages
            val sync = acquire(invitation.documentId)
            run(Link(secure, invitation.documentId, invitation.key), sync)
        } catch (_: SyncException) {
            // Refused or lost before it started
        } finally {
            channel.close()
        }
    }

    /** Closes every connection. */
    suspend fun stop() {
        mutex.withLock { links.toList() }.forEach { it.channel.close() }
    }

    // ==========================================================
    // Documents
    // ==========================================================

    /**
     * A document being synchronized: its session, and the connections receiving its operations
     * (only touched on the thread of the document).
     */
    private inner class DocumentSync(val open: OpenDocument) {
        /** Connections using it (under [mutex]). */
        var users = 1
        val links = mutableListOf<Link>()

        /** The connection whose operations are being applied (they are not sent back to it). */
        var receivingFrom: Link? = null

        val observer = ChangeObserver { committed ->
            for (link in links) if (link !== receivingFrom) link.offer(committed.operation)
        }
    }

    /** The document [documentId] being synchronized, or `null` when this device does not have it. */
    private suspend fun acquire(documentId: String): DocumentSync? = mutex.withLock {
        syncs[documentId]?.let { return it.also { it.users++ } }
        val open = documents.open(documentId) ?: return null
        start(open)
    }

    /** The document of [snapshot], received: stored (unless it arrived meanwhile), then synchronized. */
    private suspend fun adopt(snapshot: DocumentSnapshot): DocumentSync = mutex.withLock {
        syncs[snapshot.id]?.let { return it.also { it.users++ } }
        val open = documents.open(snapshot.id) ?: documents.insert(snapshot.toDocument(), receivedFolder())
        start(open)
    }

    private suspend fun start(open: OpenDocument): DocumentSync {
        val sync = DocumentSync(open)
        open.edit { it.addObserver(sync.observer) }
        syncs[open.document.id] = sync
        return sync
    }

    private suspend fun release(sync: DocumentSync) {
        val last = mutex.withLock {
            sync.users--
            (sync.users == 0).also { if (it) syncs.remove(sync.open.document.id) }
        }
        if (!last) return
        sync.open.edit { it.removeObserver(sync.observer) }
        documents.close(sync.open)
    }

    // ==========================================================
    // Connections
    // ==========================================================

    /** A connection about the document [documentId] (of key [key]). */
    private inner class Link(val channel: SyncChannel, val documentId: String, val key: DocumentKey) {
        var sync: DocumentSync? = null
        var peer: Peer? = null
        var peerHello: Hello? = null

        /** The messages to send, in order. */
        val outbox = Channel<SyncMessage>(Channel.UNLIMITED)

        /** The last operation of every replica the other device has (sent, or it had them). */
        val sent = mutableMapOf<String, Long>()

        // On the thread of the document:
        /** The operations made here are sent as they are made (the missed ones were sent). */
        var caughtUp = false
        val waiting = mutableListOf<Operation>()

        /** Operations received before the ones they follow. */
        val pending = mutableListOf<Operation>()

        // On the receiving coroutine:
        val requested = mutableSetOf<String>()
        val downloads = mutableMapOf<String, Download>()

        /** [operation] was applied to the document (on its thread). */
        fun offer(operation: Operation) {
            if (caughtUp) outbox.trySend(Operations(listOf(operation))) else waiting.add(operation)
        }
    }

    private class Download(val bytes: ByteArray) {
        var received = 0
    }

    private suspend fun run(link: Link, initial: DocumentSync?) {
        link.sync = initial
        mutex.withLock { links.add(link) }
        val sender = scope.launch { send(link) }
        try {
            val hello = initial?.open?.edit { Hello(it.replica, deviceName, it.versionVector, hasDocument = true) }
                ?: Hello(documents.replicaId(), deviceName, emptyMap(), hasDocument = false)
            link.outbox.send(hello)
            while (true) {
                val frame = link.channel.receive() ?: break
                val message = SyncCodec.decode(frame) ?: continue
                handle(link, message)
            }
        } catch (_: SyncException) {
            // The connection is lost: what was missed is exchanged at the next one
        } finally {
            sender.cancel()
            link.channel.close()
            withContext(NonCancellable) { detach(link) }
        }
    }

    private suspend fun detach(link: Link) {
        mutex.withLock {
            links.remove(link)
            // The presences it brought are gone with it
            if (lastPresences.values.removeAll { it.second === link }) presenceState.value = lastPresences.values.map { it.first }
        }
        link.peer?.let { peer -> connected.update { it - peer } }
        val sync = link.sync ?: return
        sync.open.edit { sync.links.remove(link) }
        release(sync)
    }

    /** Sends the messages of [link], the operations the other device has not got only. */
    private suspend fun send(link: Link) {
        try {
            var held: SyncMessage? = null
            while (true) {
                val message = held ?: link.outbox.receiveCatching().getOrNull() ?: break
                held = null
                val frame = if (message is Operations) {
                    // With the operations waiting after them
                    val batch = message.operations.toMutableList()
                    while (batch.size < MAX_BATCH) {
                        val next = link.outbox.tryReceive().getOrNull() ?: break
                        if (next is Operations) batch += next.operations else {
                            held = next
                            break
                        }
                    }
                    val missing = batch.filter { operation ->
                        val id = operation.id
                        (id.sequence > (link.sent[id.replica] ?: 0L)).also { if (it) link.sent[id.replica] = id.sequence }
                    }
                    if (missing.isEmpty()) continue
                    Operations(missing)
                } else {
                    message
                }
                link.channel.send(SyncCodec.encode(frame))
            }
        } catch (_: SyncException) {
            link.channel.close()
        }
    }

    private suspend fun handle(link: Link, message: SyncMessage) {
        when (message) {
            is Hello -> onHello(link, message)
            is Snapshot -> onSnapshot(link, message.snapshot)
            is Operations -> onOperations(link, message.operations)
            is ResourceRequest -> onResourceRequest(link, message.ids)
            is ResourceChunk -> onResourceChunk(link, message)
            is ResourceMissing -> link.requested.remove(message.id)
            is PresenceMessage -> onPresence(link, message.presence)
        }
    }

    /** The presence of another device: kept, and relayed to the other devices on the document. */
    private suspend fun onPresence(link: Link, presence: Presence) {
        if (presence.documentId != link.documentId) return
        val key = presence.documentId to presence.replica
        val targets = mutex.withLock {
            val last = lastPresences[key]?.first
            if (last != null && last.sequence >= presence.sequence) return
            lastPresences[key] = presence.copy(receivedAt = currentTimeMillis()) to link
            presenceState.value = lastPresences.values.map { it.first }
            links.filter { it.documentId == presence.documentId && it !== link && it.peerHello != null }
        }
        targets.forEach { it.outbox.trySend(PresenceMessage(presence)) }
    }

    private suspend fun onHello(link: Link, hello: Hello) {
        if (link.peerHello != null) return
        link.peerHello = hello
        link.sent.putAll(hello.versionVector)
        val peer = Peer(link.documentId, hello.replica, hello.name)
        link.peer = peer
        connected.update { it + peer }
        // Without the document: it comes with the snapshot
        val sync = link.sync ?: return
        catchUp(link, sync, hello)
    }

    private suspend fun onSnapshot(link: Link, snapshot: DocumentSnapshot) {
        if (link.sync != null || snapshot.id != link.documentId) return
        val sync = adopt(snapshot)
        link.sync = sync
        // Shared from now on (the key could not be kept with a document not received yet)
        shared.add(link.documentId, link.key)
        link.peerHello?.let { catchUp(link, sync, it) }
    }

    /**
     * Sends the other device what it misses: the document if it does not have it, the operations
     * it has not applied; then the operations as they are made.
     */
    private suspend fun catchUp(link: Link, sync: DocumentSync, hello: Hello) {
        // From now on the operations applied here wait for the link
        sync.open.edit { sync.links.add(link) }
        if (!hello.hasDocument) link.outbox.send(Snapshot(sync.open.edit { it.document.snapshot() }))
        val missed = sync.open.operationsAfter(if (hello.hasDocument) hello.versionVector else emptyMap())
        missed.chunked(MAX_BATCH).forEach { link.outbox.send(Operations(it)) }
        sync.open.edit {
            link.caughtUp = true
            for (operation in link.waiting) link.outbox.trySend(Operations(listOf(operation)))
            link.waiting.clear()
        }
        requestResources(link, sync)
    }

    private suspend fun onOperations(link: Link, operations: List<Operation>) {
        val sync = link.sync ?: return
        sync.open.edit { session ->
            link.pending.addAll(operations)
            sync.receivingFrom = link
            try {
                applyPending(session, link.pending)
            } finally {
                sync.receivingFrom = null
            }
        }
        requestResources(link, sync)
    }

    /**
     * Applies the operations of [pending] that follow the ones applied (each replica in order),
     * dropping the ones applied already.
     */
    private fun applyPending(session: DocumentSession, pending: MutableList<Operation>) {
        var progress = true
        while (progress && pending.isNotEmpty()) {
            progress = false
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                val operation = iterator.next()
                val last = session.lastApplied(operation.id.replica)
                if (operation.id.sequence <= last) {
                    iterator.remove()
                } else if (operation.id.sequence == last + 1) {
                    session.receive(operation)
                    iterator.remove()
                    progress = true
                }
            }
        }
    }

    // ==========================================================
    // Files of the resources
    // ==========================================================

    /** Asks the other device for the files of the resources this one misses. */
    private suspend fun requestResources(link: Link, sync: DocumentSync) {
        val missing = sync.open.edit { session -> session.document.resources.filter { it.path.isEmpty() }.map { it.id } }
            .filter { link.requested.add(it) }
        if (missing.isNotEmpty()) link.outbox.send(ResourceRequest(missing))
    }

    private suspend fun onResourceRequest(link: Link, ids: List<String>) {
        val sync = link.sync ?: return
        for (id in ids) {
            val path = sync.open.edit { it.document.findResource(id)?.path }
            val bytes = path?.let { files.read(it) }
            if (path == null || bytes == null) {
                link.outbox.send(ResourceMissing(id))
                continue
            }
            val extension = path.substringAfterLast('/').substringAfterLast('.', "")
            var offset = 0
            do {
                val end = min(offset + CHUNK_SIZE, bytes.size)
                link.outbox.send(ResourceChunk(id, extension, offset.toLong(), bytes.size.toLong(), bytes.copyOfRange(offset, end)))
                offset = end
            } while (offset < bytes.size)
        }
    }

    private suspend fun onResourceChunk(link: Link, chunk: ResourceChunk) {
        val sync = link.sync ?: return
        if (chunk.id !in link.requested || chunk.total !in 0..MAX_RESOURCE_SIZE) return
        val download = link.downloads.getOrPut(chunk.id) { Download(ByteArray(chunk.total.toInt())) }
        if (chunk.offset < 0 || chunk.offset + chunk.bytes.size > download.bytes.size) return
        chunk.bytes.copyInto(download.bytes, chunk.offset.toInt())
        download.received += chunk.bytes.size
        if (download.received < download.bytes.size) return
        link.downloads.remove(chunk.id)
        val path = files.write(download.bytes, chunk.extension) ?: return
        sync.open.resourceReceived(chunk.id, path)
    }

    private companion object {
        /** The most operations in a message. */
        const val MAX_BATCH = 200

        const val CHUNK_SIZE = 256 * 1024
        const val MAX_RESOURCE_SIZE = 512L * 1024 * 1024
    }
}
