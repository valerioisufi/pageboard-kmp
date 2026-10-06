package io.github.valerioisufi.drawview.sync

import io.github.valerioisufi.drawview.core.attachment.AttachmentChange
import io.github.valerioisufi.drawview.core.attachment.AttachmentReceiver
import io.github.valerioisufi.drawview.core.attachment.DocumentAttachments
import io.github.valerioisufi.drawview.core.change.ChangeObserver
import io.github.valerioisufi.drawview.core.change.ChangeOrigin
import io.github.valerioisufi.drawview.core.change.DocumentSession
import io.github.valerioisufi.drawview.core.change.DocumentSnapshot
import io.github.valerioisufi.drawview.core.change.Operation
import io.github.valerioisufi.drawview.core.change.TextData
import io.github.valerioisufi.drawview.core.change.snapshot
import io.github.valerioisufi.drawview.core.util.currentTimeMillis
import io.github.valerioisufi.drawview.data.OpenDocument
import io.github.valerioisufi.drawview.data.OpenDocuments
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A device working on the document [documentId] with this one. */
data class Peer(val documentId: String, val replica: String, val name: String)

/**
 * Keeps the shared documents of this device the same as on the other devices (GRASP Controller
 * of working on a document with others): over every connection ([accept], [join]) the two
 * devices send each other the operations the other misses, then the operations as they are made
 * (the ones of the other devices too: a device relays them), and the files of the resources.
 *
 * The data the extensions of the editor keep beside the documents ([attachments], e.g. the
 * lectures) travel too, in items whose versions the devices compare.
 *
 * The documents are changed through their [OpenDocuments] session, open in an editor or not;
 * the connections come from any [SyncTransport] and are encrypted with the key of their document
 * ([SecureChannel]). The documents received from the other devices are stored in the folder
 * [receivedFolder] gives.
 *
 * [scope] runs the connections (a supervisor scope: a connection failing does not stop the
 * others). A connection with nothing to send says it is still there every [keepAliveMs]; one the
 * other device has said nothing on for three times as long is lost (a device gone without closing
 * it: out of the network, asleep...) and closed, so that it can be made again. `0`: never.
 */
class SyncEngine(
    private val documents: OpenDocuments,
    private val shared: SharedDocuments,
    private val scope: CoroutineScope,
    private val deviceName: String,
    private val files: ResourceFiles = PlatformResourceFiles,
    private val keepAliveMs: Long = 4_000,
    private val attachments: DocumentAttachments? = null,
    private val receivedFolder: suspend () -> String? = { null }
) {
    private val mutex = Mutex()
    private val syncs = mutableMapOf<String, DocumentSync>()
    private val links = mutableSetOf<Link>()
    private val connected = MutableStateFlow<List<Peer>>(emptyList())

    /** The devices connected, with the document each one works on. */
    val peers: StateFlow<List<Peer>> = connected.asStateFlow()

    private val otherVersion = MutableStateFlow(false)

    /**
     * Whether a device with another version of the application tried to work with this one: what
     * it sends cannot be read, so its connections are closed (the devices need the same version).
     */
    val otherVersionMet: StateFlow<Boolean> = otherVersion.asStateFlow()

    init {
        // The items beside the documents changed here: to the devices on their document
        attachments?.let { scope.launch { it.changes.collect(::onAttachmentChanged) } }
    }

    /** The last presence of every device on every document, and the connection it came from. */
    private val lastPresences = mutableMapOf<Pair<String, String>, Pair<Presence, Link>>()
    private val presenceState = MutableStateFlow<List<Presence>>(emptyList())
    private var presenceSequence = 0L

    /**
     * Where the other devices are on the documents (see [Presence]); a device that stops sending
     * its presence for a while is gone (see [Presence.receivedAt]).
     */
    val presences: StateFlow<List<Presence>> = presenceState.asStateFlow()

    /** The replica of this device (asked once). */
    private var ownReplica: String? = null

    private suspend fun replica(): String = ownReplica ?: documents.replicaId().also { ownReplica = it }

    /** What was sent of the presence on every document (see [updatePresence]). */
    private class SentPresence(
        var stroke: Long = 0,
        var inputs: Int = 0,
        var text: TextData? = null,
        var selectionIds: List<String>? = null,
        var wholeAt: Long = 0
    )

    private val sentPresences = mutableMapOf<String, SentPresence>()

    /**
     * Tells the devices working on the document of [presence] where this one is. Of the stroke
     * being drawn only the inputs not sent yet travel, the text written and the elements selected
     * only when they change; all of it now and then (for the devices arrived meanwhile).
     */
    suspend fun updatePresence(presence: Presence) {
        val replica = replica()
        val (message, targets) = mutex.withLock {
            val now = currentTimeMillis()
            presenceSequence = maxOf(presenceSequence + 1, now)
            val sent = sentPresences.getOrPut(presence.documentId) { SentPresence() }
            val whole = now - sent.wholeAt >= WHOLE_PRESENCE_MS
            if (whole) sent.wholeAt = now
            val inputs = presence.inkInputs
            val from = if (!whole && presence.stroke != 0L && presence.stroke == sent.stroke && inputs >= sent.inputs) sent.inputs else 0
            val textKept = !whole && presence.text != null && presence.text == sent.text
            val selectionIds = presence.selection?.ids
            val selectionKept = !whole && selectionIds != null && selectionIds == sent.selectionIds
            sent.stroke = presence.stroke
            sent.inputs = inputs
            sent.text = presence.text
            sent.selectionIds = selectionIds
            val part = presence.copy(
                ink = presence.ink.subList(from * Presence.INK_VALUES, presence.ink.size).toList(),
                text = presence.text.takeUnless { textKept },
                selection = presence.selection?.let { if (selectionKept) it.copy(ids = emptyList()) else it },
                replica = replica,
                name = deviceName,
                sequence = presenceSequence
            )
            PresenceMessage(part, from, textKept, selectionKept) to links.filter { it.documentId == presence.documentId && it.peerHello != null }
        }
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
            if (open.protocol != PROTOCOL_VERSION) {
                otherVersion.value = true
                return@launch
            }
            val (documentId, key) = shared.all().entries.firstOrNull { it.value.tag == open.tag } ?: return@launch
            val secure = SecureChannel.of(channel, key)
            // Without the document yet (joined through an invitation): it comes with the first messages
            val sync = acquire(documentId)
            run(Link(secure, documentId, key, opened = false), sync)
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
            run(Link(secure, invitation.documentId, invitation.key, opened = true), sync)
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
            // The ones this device makes meanwhile (e.g. merging them, see ChangeOrigin.MERGE) go to it too
            for (link in links) if (link !== receivingFrom || committed.origin != ChangeOrigin.REMOTE) link.offer(committed.operation)
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

    /** A connection about the document [documentId] (of key [key]), [opened] by this device or by the other one. */
    private inner class Link(val channel: SyncChannel, val documentId: String, val key: DocumentKey, val opened: Boolean) {
        var sync: DocumentSync? = null
        var peer: Peer? = null
        var peerHello: Hello? = null

        /** When the last frame came (it says the connection is alive). */
        var receivedAt = currentTimeMillis()

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
        val receiving = mutableMapOf<String, Receiving>()

        /** The items beside the document asked for, sent one at a time ([sendAttachment]). */
        val attachmentRequests = Channel<String>(Channel.UNLIMITED)

        /** The parts of the items queued and not sent yet: a few at a time (a long audio is not read at once). */
        val attachmentParts = Semaphore(ATTACHMENT_PARTS_AHEAD)

        /** [operation] was applied to the document (on its thread). */
        fun offer(operation: Operation) {
            if (caughtUp) outbox.trySend(Operations(listOf(operation))) else waiting.add(operation)
        }
    }

    private class Download(val bytes: ByteArray) {
        var received = 0
    }

    /** An item beside the document being received: [received] of its [size] bytes, at [version]. */
    private class Receiving(val receiver: AttachmentReceiver, val version: String, val size: Long) {
        var received = 0L
    }

    private suspend fun run(link: Link, initial: DocumentSync?) {
        link.sync = initial
        mutex.withLock { links.add(link) }
        val sender = scope.launch { send(link) }
        val transfers = attachments?.let { scope.launch { for (key in link.attachmentRequests) sendAttachment(link, key) } }
        try {
            val hello = initial?.open?.edit { Hello(it.replica, deviceName, it.versionVector, hasDocument = true) }
                ?: Hello(documents.replicaId(), deviceName, emptyMap(), hasDocument = false)
            link.outbox.send(hello)
            while (true) {
                // Closed, or silent for too long: lost
                val frame = (if (keepAliveMs > 0) withTimeoutOrNull(keepAliveMs * 3) { link.channel.receive() } else link.channel.receive()) ?: break
                link.receivedAt = currentTimeMillis()
                val message = SyncCodec.decode(frame)
                if (message == null) {
                    // Of another version of the application: skipping it would leave the
                    // operations after it waiting for ever
                    otherVersion.value = true
                    break
                }
                handle(link, message)
            }
        } catch (_: SyncException) {
            // The connection is lost: what was missed is exchanged at the next one
        } finally {
            sender.cancel()
            transfers?.cancel()
            link.channel.close()
            withContext(NonCancellable) { detach(link) }
        }
    }

    private suspend fun detach(link: Link) {
        link.receiving.values.forEach { it.receiver.cancel() }
        link.receiving.clear()
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
                val message: SyncMessage = held ?: if (keepAliveMs > 0) {
                    // Nothing to send for a while: still there
                    val received = withTimeoutOrNull(keepAliveMs) { link.outbox.receiveCatching() }
                    if (received == null) Ping else received.getOrNull() ?: break
                } else {
                    link.outbox.receiveCatching().getOrNull() ?: break
                }
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
                if (frame is AttachmentPart) link.attachmentParts.release()
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
            is PresenceMessage -> onPresence(link, message)
            is AttachmentVersions -> onAttachmentVersions(link, message.versions)
            is AttachmentRequest -> message.keys.forEach { link.attachmentRequests.trySend(it) }
            is AttachmentPart -> onAttachmentPart(link, message)
            Ping -> Unit
        }
    }

    /**
     * The presence of another device: kept with the whole stroke it is drawing, and relayed to the
     * other devices on the document.
     */
    private suspend fun onPresence(link: Link, message: PresenceMessage) {
        val presence = message.presence
        // Its own, back through the others (connected with each other too)
        if (presence.documentId != link.documentId || presence.replica == replica()) return
        val key = presence.documentId to presence.replica
        val targets = mutex.withLock {
            val last = lastPresences[key]?.first
            if (last != null && last.sequence >= presence.sequence) return
            val from = message.inkFrom * Presence.INK_VALUES
            val ink = when {
                from == 0 -> presence.ink
                last != null && last.stroke == presence.stroke && last.ink.size >= from -> last.ink.subList(0, from) + presence.ink
                // Inputs missing: the stroke as it was, until all of it comes again
                last != null && last.stroke == presence.stroke -> last.ink
                else -> emptyList()
            }
            // The same text and selection as before (missing: until all of them come again)
            val text = if (message.textKept) last?.text else presence.text
            val selection = presence.selection?.let { if (message.selectionKept) it.copy(ids = last?.selection?.ids.orEmpty()) else it }
            lastPresences[key] = presence.copy(ink = ink, text = text, selection = selection, receivedAt = currentTimeMillis()) to link
            presenceState.value = lastPresences.values.map { it.first }
            links.filter { it.documentId == presence.documentId && it !== link && it.peerHello != null }
        }
        targets.forEach { it.outbox.trySend(message) }
    }

    private suspend fun onHello(link: Link, hello: Hello) {
        val twin = mutex.withLock {
            if (link.peerHello != null) return
            link.peerHello = hello
            links.firstOrNull { it !== link && it.documentId == link.documentId && it.peerHello?.replica == hello.replica }
        }
        // Two connections with the same device (both connected to the other at once): one is enough.
        // Not when the other one is silent: maybe lost (the device started again), it closes itself.
        val alive = keepAliveMs == 0L || twin != null && currentTimeMillis() - twin.receivedAt < keepAliveMs * 3 / 2
        if (twin != null && alive) {
            val surplus = surplusOf(link, twin, hello.replica)
            surplus?.channel?.close()
            if (surplus === link) return
        }
        link.sent.putAll(hello.versionVector)
        val peer = Peer(link.documentId, hello.replica, hello.name)
        link.peer = peer
        connected.update { it + peer }
        // The next presence whole, for the device arrived
        mutex.withLock { sentPresences[link.documentId]?.wholeAt = 0 }
        // Without the document: it comes with the snapshot
        val sync = link.sync ?: return
        catchUp(link, sync, hello)
    }

    /**
     * Which one of the connections [link] (the newer) and [twin] with the device [replica] to
     * close, the same one on both devices: the one opened by the device with the greater replica,
     * or the newer one when the same device opened both (closed by it alone).
     */
    private suspend fun surplusOf(link: Link, twin: Link, replica: String): Link? {
        val mine = replica()
        fun openerOf(it: Link) = if (it.opened) mine else replica
        return when {
            openerOf(link) != openerOf(twin) -> if (openerOf(link) == minOf(mine, replica)) twin else link
            link.opened -> link
            else -> null
        }
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
        // Then the items beside the document
        attachments?.let { link.outbox.send(AttachmentVersions(it.versions(link.documentId))) }
    }

    private suspend fun onOperations(link: Link, operations: List<Operation>) {
        val sync = link.sync ?: return
        // Prepared first, at the same time (e.g. the strokes modeled again: slow), so that the
        // document waits less
        coroutineScope {
            for (operation in operations) launch { operation.change.prepare() }
        }
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

    // ==========================================================
    // Items beside the documents
    // ==========================================================

    /** The items the other device has: the ones wanted here and different are asked for. */
    private suspend fun onAttachmentVersions(link: Link, versions: Map<String, String>) {
        val attachments = attachments ?: return
        if (link.sync == null) return
        val mine = attachments.versions(link.documentId)
        val wanted = versions.filter { (key, version) -> mine[key] != version && attachments.wants(link.documentId, key, version) }.keys
        if (wanted.isNotEmpty()) link.outbox.send(AttachmentRequest(wanted.toList()))
    }

    /** Sends the item [key] asked for, in parts. */
    private suspend fun sendAttachment(link: Link, key: String) {
        val attachments = attachments ?: return
        val version = attachments.version(link.documentId, key) ?: return
        val size = attachments.size(link.documentId, key) ?: return
        var offset = 0L
        do {
            val bytes = attachments.read(link.documentId, key, offset, min(CHUNK_SIZE.toLong(), size - offset).toInt()) ?: return
            link.attachmentParts.acquire()
            link.outbox.send(AttachmentPart(key, version, offset, size, bytes))
            offset += bytes.size
        } while (offset < size && bytes.isNotEmpty())
    }

    private suspend fun onAttachmentPart(link: Link, part: AttachmentPart) {
        val attachments = attachments ?: return
        if (link.sync == null || part.size !in 0..MAX_ATTACHMENT_SIZE) return
        if (part.offset == 0L) {
            link.receiving.remove(part.key)?.receiver?.cancel()
            val receiver = attachments.receive(link.documentId, part.key, part.version, part.size) ?: return
            link.receiving[part.key] = Receiving(receiver, part.version, part.size)
        }
        val receiving = link.receiving[part.key] ?: return
        if (part.version != receiving.version || part.offset != receiving.received) {
            // Not the next part: the item is asked for again at its next change
            link.receiving.remove(part.key)
            receiving.receiver.cancel()
            return
        }
        receiving.receiver.write(part.bytes)
        receiving.received += part.bytes.size
        if (receiving.received < receiving.size) return
        link.receiving.remove(part.key)
        receiving.receiver.complete()
    }

    /** An item beside the document [AttachmentChange.documentId] changed here: the others are told. */
    private suspend fun onAttachmentChanged(change: AttachmentChange) {
        val targets = mutex.withLock { links.filter { it.documentId == change.documentId && it.peerHello != null && it.sync != null } }
        for (link in targets) link.outbox.send(AttachmentVersions(mapOf(change.key to change.version)))
    }

    private companion object {
        /** The most operations in a message. */
        const val MAX_BATCH = 200

        const val CHUNK_SIZE = 256 * 1024

        /** How often the whole presence is sent again (see [updatePresence]). */
        const val WHOLE_PRESENCE_MS = 500L
        const val MAX_RESOURCE_SIZE = 512L * 1024 * 1024
        const val MAX_ATTACHMENT_SIZE = 4L * 1024 * 1024 * 1024
        const val ATTACHMENT_PARTS_AHEAD = 4
    }
}
