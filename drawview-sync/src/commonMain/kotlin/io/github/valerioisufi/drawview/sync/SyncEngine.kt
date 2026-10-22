package io.github.valerioisufi.drawview.sync

import dev.whyoleg.cryptography.random.CryptographyRandom
import io.github.valerioisufi.drawview.model.AttachmentChange
import io.github.valerioisufi.drawview.model.AttachmentReceiver
import io.github.valerioisufi.drawview.model.DocumentAttachments
import io.github.valerioisufi.drawview.model.change.ChangeObserver
import io.github.valerioisufi.drawview.model.change.ChangeOrigin
import io.github.valerioisufi.drawview.model.change.DocumentSession
import io.github.valerioisufi.drawview.model.change.DocumentSnapshot
import io.github.valerioisufi.drawview.model.change.Operation
import io.github.valerioisufi.drawview.model.change.TextData
import io.github.valerioisufi.drawview.model.change.snapshot
import io.github.valerioisufi.drawview.platform.currentTimeMillis
import io.github.valerioisufi.drawview.store.OpenDocument
import io.github.valerioisufi.drawview.store.OpenDocuments
import io.github.valerioisufi.drawview.sync.protocol.DeviceAddress
import io.github.valerioisufi.drawview.sync.protocol.DeviceIdentity
import io.github.valerioisufi.drawview.sync.protocol.DocumentKey
import io.github.valerioisufi.drawview.sync.protocol.Invitation
import io.github.valerioisufi.drawview.sync.protocol.MAX_FRAME_SIZE
import io.github.valerioisufi.drawview.sync.protocol.Member
import io.github.valerioisufi.drawview.sync.protocol.Membership
import io.github.valerioisufi.drawview.sync.protocol.OpenFrame
import io.github.valerioisufi.drawview.sync.protocol.OperationSeal
import io.github.valerioisufi.drawview.sync.protocol.PROTOCOL_VERSION
import io.github.valerioisufi.drawview.sync.protocol.Role
import io.github.valerioisufi.drawview.sync.protocol.Route
import io.github.valerioisufi.drawview.sync.protocol.SealedOperation
import io.github.valerioisufi.drawview.sync.protocol.SecureChannel
import io.github.valerioisufi.drawview.sync.protocol.SignedMembership
import io.github.valerioisufi.drawview.sync.protocol.SyncChannel
import io.github.valerioisufi.drawview.sync.protocol.SyncException
import io.github.valerioisufi.drawview.sync.protocol.SyncTransport
import kotlin.math.min
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A device working on the document [documentId] with this one ([device]: its identity, see [DeviceIdentity]). */
public data class Peer(val documentId: String, val replica: String, val name: String, val device: String = "")

/**
 * A device asking to join the document [documentId] with an invitation needing the approval of
 * the owner (see [SyncEngine.answer]), as long as it stays connected.
 */
public class JoinRequest internal constructor(public val documentId: String, public val device: String, public val name: String, public val role: Role) {
    internal val decision = CompletableDeferred<Boolean>()
}

/** Where this device is in joining a document with an invitation. */
public enum class JoinStatus { LOOKING_FOR_OWNER, WAITING_APPROVAL, ADMITTED, REFUSED }

/**
 * Keeps the shared documents of this device the same as on the other devices (GRASP Controller
 * of working on a document with others): over every connection ([accept], [connect]) the two
 * devices prove who they are ([identity]), check that the other one is a member of the document
 * ([DocumentSharing]), then send each other the operations the other misses, then the operations
 * as they are made (the ones of the other devices too: a device relays them), and the files of
 * the resources. The operations of a device that may not change the document ([Role.READER], a
 * device removed) are refused by every device.
 *
 * The owner of a document admits the devices joining with its invitations ([invite]), at once or
 * once approved ([joinRequests]), gives them their roles, removes them (the document gets a new
 * key: a device removed cannot follow it anymore) and ends the sharing; every device follows the
 * membership the owner signs.
 *
 * The data the extensions of the editor keep beside the documents ([attachments], e.g. the
 * lectures) travel too, in items whose versions the devices compare; a transfer interrupted goes
 * on from where it stopped. The operations every member has are removed from the log
 * (see [OpenDocument.compact]).
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
 *
 * The members of the documents give each other where they can be reached ([addresses], see
 * [publishAddress]), kept in [addressBook]: the controllers of the transports connect to them.
 */
public class SyncEngine(
    private val documents: OpenDocuments,
    shared: SharedDocuments,
    private val scope: CoroutineScope,
    private val deviceName: () -> String,
    private val files: ResourceFiles = PlatformResourceFiles,
    private val keepAliveMs: Long = 4_000,
    private val attachments: DocumentAttachments? = null,
    identity: DeviceIdentity = DeviceIdentity.generate(),
    private val receivedFolder: suspend () -> String? = { null },
    private val addressBook: AddressBook = InMemoryAddressBook()
) {
    private val sharing = DocumentSharing(shared, identity)
    private val identity get() = sharing.identity
    private val mutex = Mutex()
    private val syncs = mutableMapOf<String, DocumentSync>()
    private val links = mutableSetOf<Link>()
    private val connected = MutableStateFlow<List<Peer>>(emptyList())

    /** The devices connected (members of the document of the connection), with the document each one works on. */
    public val peers: StateFlow<List<Peer>> = connected.asStateFlow()

    /** This device among the members of the documents ([DeviceIdentity.publicKey]). */
    public val device: String get() = identity.publicKey

    /** The memberships of the shared documents (who works on each one, as the owner decided). */
    public val memberships: StateFlow<Map<String, Membership>> get() = sharing.memberships

    /** Documents whose keys or invitations changed: what to announce on the network (see [reachable]). */
    public val sharingChanged: SharedFlow<String> get() = sharing.changed

    private val requests = MutableStateFlow<List<JoinRequest>>(emptyList())

    /** The devices waiting for the approval of this device, the owner, to join a document. */
    public val joinRequests: StateFlow<List<JoinRequest>> = requests.asStateFlow()

    private val joinStates = MutableStateFlow<Map<String, JoinStatus>>(emptyMap())

    /** The documents this device is joining with an invitation, and where it is. */
    public val joining: StateFlow<Map<String, JoinStatus>> = joinStates.asStateFlow()

    private val otherVersion = MutableStateFlow(false)

    /**
     * Whether a device with another version of the application tried to work with this one: what
     * it sends cannot be read, so its connections are closed (the devices need the same version).
     */
    public val otherVersionMet: StateFlow<Boolean> = otherVersion.asStateFlow()

    /**
     * The incoming channels of devices not known yet (before their first frame decrypted), a few
     * at a time: each one may hold a thread of its transport and the frames it sends.
     */
    private val strangers = Semaphore(MAX_STRANGERS)

    /** The downloads of the files of the resources, by document and resource (they go on over another connection). */
    private val downloads = mutableMapOf<Pair<String, String>, Download>()
    private val downloadsMutex = Mutex()

    private val known = KnownAddresses()
    private val own = MutableStateFlow<Map<String, DeviceAddress>>(emptyMap())
    private val addressesLoaded = CompletableDeferred<Unit>()
    private val addressesMutex = Mutex()

    /**
     * Where the other members of the shared documents can be reached, by device ([Member.device])
     * and transport: what they said, signed (see [DeviceAddress]), kept from one start to the next.
     */
    public val addresses: StateFlow<Map<String, Map<String, DeviceAddress>>> get() = known.all

    /** Where this device can be reached, by transport (see [publishAddress]). */
    public val ownAddresses: StateFlow<Map<String, DeviceAddress>> = own.asStateFlow()

    init {
        scope.launch { loadAddresses() }
        attachments?.let { attachments ->
            // The items beside the documents changed here: to the devices on their document
            scope.launch { attachments.changes.collect(::onAttachmentChanged) }
            scope.launch { attachments.wantedAgain.collect { documentId -> linksOf(documentId).forEach { askForOffered(it) } } }
        }
    }

    /** The last presence of every device on every document, and the connection it came from. */
    private val lastPresences = mutableMapOf<Pair<String, String>, Pair<Presence, Link>>()
    private val presenceState = MutableStateFlow<List<Presence>>(emptyList())
    private var presenceSequence = 0L

    /**
     * Where the other devices are on the documents (see [Presence]); a device that stops sending
     * its presence for a while is gone (see [Presence.receivedAt]).
     */
    public val presences: StateFlow<List<Presence>> = presenceState.asStateFlow()

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
    public suspend fun updatePresence(presence: Presence) {
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
                name = deviceName(),
                sequence = presenceSequence
            )
            PresenceMessage(part, from, textKept, selectionKept) to links.filter { it.documentId == presence.documentId && it.authorized }
        }
        targets.forEach { it.trySend(message) }
    }

    // ==========================================================
    // Sharing
    // ==========================================================

    /**
     * Shares the document [documentId] (titled [title]), this device becoming its owner (unless it
     * is shared already): an invitation for the devices joining as [role], approved first by this
     * device when [approval], reaching this one at [addresses] (by transport: where it is now, by
     * default, see [publishAddress]).
     *
     * @return `null` when this device is not the owner of the document (only the owner invites).
     */
    public suspend fun invite(
        documentId: String,
        title: String,
        role: Role = Role.EDITOR,
        approval: Boolean = false,
        addresses: Map<String, String> = ownAddressesByTransport()
    ): Invitation? {
        val state = sharing.stateOf(documentId)
        if (state?.members == null) sharing.share(documentId, replica(), deviceName())
        val grant = sharing.invite(documentId, role, approval) ?: return null
        return Invitation(documentId, title, DocumentKey.decode(grant.key)!!, DeviceIdentity.fingerprint(device), addresses)
    }

    /** The invitations of the document [documentId] (this device is its owner). */
    public suspend fun invitationsOf(documentId: String): List<InvitationGrant> = sharing.stateOf(documentId)?.invitations.orEmpty()

    /** The invitation [grant] as a link to give, reaching this device at [addresses] (by transport). */
    public fun invitationOf(
        documentId: String,
        title: String,
        grant: InvitationGrant,
        addresses: Map<String, String> = ownAddressesByTransport()
    ): Invitation = Invitation(documentId, title, DocumentKey.decode(grant.key)!!, DeviceIdentity.fingerprint(device), addresses)

    private fun ownAddressesByTransport(): Map<String, String> = own.value.mapValues { it.value.address }

    /** Revokes the invitation [grantId] of [documentId]: nobody joins with it anymore (the devices joined stay). */
    public suspend fun revokeInvitation(documentId: String, grantId: String) {
        sharing.revoke(documentId, grantId)
        linksOf(documentId).filter { (it.match as? TagMatch.Grant)?.grant?.id == grantId && !it.authorized }.forEach { it.channel.close() }
    }

    /** Approves (or refuses) the device of [request] joining its document. */
    public fun answer(request: JoinRequest, approve: Boolean) {
        requests.update { it - request }
        request.decision.complete(approve)
    }

    /** Gives the member [target] of [documentId] the role [role] (this device is the owner). */
    public suspend fun setRole(documentId: String, target: String, role: Role) {
        val member = sharing.membershipOf(documentId)?.memberOf(target) ?: return
        val state = sharing.setRole(documentId, target, role, lastSequenceOf(documentId, member.replica)) ?: return
        onMembershipChanged(documentId, state, from = null)
    }

    /**
     * Removes the member [target] from [documentId] (this device is the owner): the document gets a
     * new key, given to the other members; the device removed cannot follow it anymore.
     */
    public suspend fun removeMember(documentId: String, target: String) {
        val member = sharing.membershipOf(documentId)?.memberOf(target) ?: return
        val state = sharing.remove(documentId, target, lastSequenceOf(documentId, member.replica)) ?: return
        onMembershipChanged(documentId, state, from = null)
    }

    /**
     * Stops sharing the document [documentId]: the owner ends the sharing for every device (each one
     * keeps its copy), another device leaves it (the owner removes it). Its connections are closed.
     */
    public suspend fun unshare(documentId: String) {
        val state = sharing.stateOf(documentId)
        if (state?.owner == device && state.members != null) {
            sharing.end(documentId)?.let { onMembershipChanged(documentId, it, from = null) }
        } else {
            linksOf(documentId).filter { it.authorized }.forEach { it.trySend(Leave) }
        }
        sharing.forget(documentId)
        joinStates.update { it - documentId }
        // The addresses of the devices no longer members of any document go
        scope.launch { saveAddresses() }
        // The last messages first
        linksOf(documentId).forEach { closeSoon(it) }
    }

    /** The last operation of [replica] on [documentId] this device has. */
    private suspend fun lastSequenceOf(documentId: String, replica: String): Long {
        val open = documents.open(documentId) ?: return 0L
        return try {
            open.edit { it.lastApplied(replica) }
        } finally {
            documents.close(open)
        }
    }

    /**
     * What this device connects with (see [connect]): the keys of the documents, the current one
     * first, and the invitations it joins with; [announced]: what it announces, also the invitations
     * it gives (as the owner).
     */
    internal suspend fun reachable(announced: Boolean): List<TagMatch> = sharing.reachable(announced)

    /** The tags this device answers to on the network (see [DocumentKey.tag]). */
    public suspend fun announcedTags(): Set<String> = reachable(announced = true).mapTo(mutableSetOf()) { it.key.tag }

    /** The documents and keys to connect with, and the documents joined (see [connect]). */
    public suspend fun connectionKeys(): List<Pair<String, DocumentKey>> = reachable(announced = false).map { it.documentId to it.key }

    /** Joins the document of [invitation]: the owner admits this device when it reaches it (see [connect]). */
    public suspend fun join(invitation: Invitation) {
        sharing.startJoining(invitation)
        joinStates.update { it + (invitation.documentId to JoinStatus.LOOKING_FOR_OWNER) }
    }

    /**
     * Serves a channel another device opened to this one (see [SyncTransport.incoming]). Anyone
     * may open one: 16 at once until they show they have a key (the others are closed
     * at once), their first frame within the time a connection is lost in, and small frames until
     * the device is authorized.
     */
    public fun accept(channel: SyncChannel): Job = scope.launch {
        // Too many devices not known yet: refused at once
        if (!strangers.tryAcquire()) {
            channel.close()
            return@launch
        }
        var stranger = true
        val known = {
            if (stranger) {
                stranger = false
                strangers.release()
            }
        }
        try {
            val open = receiveLive(channel, OpenFrame.MAX_SIZE)?.let(OpenFrame::decode) ?: return@launch
            if (open.protocol != PROTOCOL_VERSION) {
                otherVersion.value = true
                return@launch
            }
            val match = sharing.match(open.tag) ?: return@launch
            // A device joins through the owner only
            if (match is TagMatch.Joining) return@launch
            val secure = SecureChannel.of(channel, match.key)
            run(Link(secure, match, opened = false), acquire(match.documentId), known)
        } catch (_: SyncException) {
            // Refused or lost before it started
        } finally {
            known()
            channel.close()
        }
    }

    /**
     * Works on the document [documentId] through [channel] (opened to a device that has it) with
     * the key [key]: a key of the document, or the one of the invitation this device joins with.
     */
    public fun connect(channel: SyncChannel, documentId: String, key: DocumentKey): Job = scope.launch {
        try {
            val match = sharing.match(key.tag)?.takeIf { it.documentId == documentId } ?: return@launch
            channel.send(OpenFrame(PROTOCOL_VERSION, key.tag).encode())
            val secure = SecureChannel.of(channel, key)
            run(Link(secure, match, opened = true), acquire(documentId))
        } catch (_: SyncException) {
            // Refused or lost before it started
        } finally {
            channel.close()
        }
    }

    /**
     * What the connection with the device [device] about [documentId] goes through now, `null`
     * when they are not connected.
     */
    public suspend fun routeOf(documentId: String, device: String): Route? = mutex.withLock {
        links.firstOrNull { it.documentId == documentId && it.authorized && it.peer?.device == device }?.channel?.route
    }

    /** Closes every connection. */
    public suspend fun stop() {
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
    private suspend fun adopt(snapshot: DocumentSnapshot, base: Map<String, Long>): DocumentSync = mutex.withLock {
        syncs[snapshot.id]?.let { return it.also { it.users++ } }
        val open = documents.open(snapshot.id) ?: documents.insert(snapshot.toDocument(documents.strokeModeller), receivedFolder(), base)
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

    private suspend fun linksOf(documentId: String): List<Link> = mutex.withLock { links.filter { it.documentId == documentId } }

    // ==========================================================
    // Connections
    // ==========================================================

    /** A connection about the document of [match] (a key of it, or an invitation), [opened] by this device or by the other one. */
    private inner class Link(val channel: SyncChannel, val match: TagMatch, val opened: Boolean) {
        val documentId: String get() = match.documentId
        val tag: String get() = match.key.tag
        var sync: DocumentSync? = null
        var peer: Peer? = null
        var peerHello: Hello? = null

        /** The challenge of this device (see [Challenge]). */
        val nonce: ByteArray = CryptographyRandom.nextBytes(NONCE_SIZE)

        /** The other device is a member, its identity proven: the document is exchanged. */
        var authorized = false

        /** The device of this connection waits for the owner to approve it (see [joinRequests]). */
        var request: JoinRequest? = null

        /** The other device has a more recent membership: it comes before the other device is checked. */
        var waitingMembership = false

        /** When the last frame came (it says the connection is alive). */
        var receivedAt = currentTimeMillis()

        /** What to send, in order. */
        val outbox = Channel<Outgoing>(Channel.UNLIMITED)

        /** Seals and opens the operations with the key of this connection. */
        val seal = OperationSeal(match.key)

        suspend fun send(message: SyncMessage) = outbox.send(OutgoingMessage(message))

        fun trySend(message: SyncMessage) = outbox.trySend(OutgoingMessage(message))

        /** The last operation of every replica the other device has (sent, or it had them). */
        val sent = mutableMapOf<String, Long>()

        // On the thread of the document:
        /** The operations made here are sent as they are made (the missed ones were sent). */
        var caughtUp = false
        val waiting = mutableListOf<Operation>()

        /** Operations received before the ones they follow. */
        val pending = mutableListOf<Operation>()

        /** Operations of devices not in the membership known here (on the receiving coroutine): applied once it comes. */
        val deferred = mutableListOf<Operation>()

        // On the receiving coroutine:
        val requested = mutableSetOf<String>()
        val receiving = mutableMapOf<String, Receiving>()

        /** The items of the other device not the same here, by key with their versions, and the ones asked for (under [attachmentLock]). */
        val offered = mutableMapOf<String, String>()
        val asked = mutableSetOf<String>()
        val attachmentLock = Mutex()

        /** The items beside the document asked for, sent one at a time ([sendAttachment]). */
        val attachmentRequests = Channel<AttachmentAsk>(Channel.UNLIMITED)

        /** The parts of the items queued and not sent yet: a few at a time (a long audio is not read at once). */
        val attachmentParts = Semaphore(ATTACHMENT_PARTS_AHEAD)

        /** [operation] was applied to the document (on its thread). */
        fun offer(operation: Operation) {
            if (caughtUp) outbox.trySend(OutgoingOperations(listOf(operation))) else waiting.add(operation)
        }
    }

    /** What a connection sends: a message, or operations applied here (sealed when they are sent). */
    private sealed interface Outgoing

    private class OutgoingMessage(val message: SyncMessage) : Outgoing

    private class OutgoingOperations(val operations: List<Operation>) : Outgoing

    /** A download of the file of a resource: [received] of its bytes (it goes on over another connection). */
    private class Download(val bytes: ByteArray) {
        var received = 0
    }

    /** An item beside the document being received: [received] of its [size] bytes, at [version]. */
    private class Receiving(val receiver: AttachmentReceiver, val version: String, val size: Long) {
        var received = 0L
    }

    /**
     * The next frame of [channel], at most [maxSize] bytes; `null` when it is closed or silent for
     * too long (lost, see [keepAliveMs]).
     */
    private suspend fun receiveLive(channel: SyncChannel, maxSize: Int): ByteArray? =
        if (keepAliveMs > 0) withTimeoutOrNull(keepAliveMs * 3) { channel.receive(maxSize) } else channel.receive(maxSize)

    /** Works through [link]; [known] runs at each frame decrypted (the other device has the key). */
    private suspend fun run(link: Link, initial: DocumentSync?, known: () -> Unit = {}) {
        link.sync = initial
        mutex.withLock { links.add(link) }
        val sender = scope.launch { send(link) }
        val transfers = attachments?.let { scope.launch { for (ask in link.attachmentRequests) sendAttachment(link, ask) } }
        try {
            if (link.match is TagMatch.Joining) joinStates.update { it + (link.documentId to JoinStatus.LOOKING_FOR_OWNER) }
            link.send(Challenge(link.nonce))
            while (true) {
                // Closed, or silent for too long: lost. Small frames until the other device is authorized
                val frame = receiveLive(link.channel, if (link.authorized) MAX_FRAME_SIZE else HANDSHAKE_FRAME_SIZE) ?: break
                known()
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
        // Gone before the owner approved it
        link.request?.let { request ->
            requests.update { it - request }
            request.decision.complete(false)
        }
        // Kept by the extension to go on later
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
            var held: Outgoing? = null
            while (true) {
                val item: Outgoing = held ?: if (keepAliveMs > 0) {
                    // Nothing to send for a while: still there
                    val received = withTimeoutOrNull(keepAliveMs) { link.outbox.receiveCatching() }
                    if (received == null) OutgoingMessage(Ping) else received.getOrNull() ?: break
                } else {
                    link.outbox.receiveCatching().getOrNull() ?: break
                }
                held = null
                val frame = when (item) {
                    is OutgoingOperations -> {
                        // With the operations waiting after them
                        val batch = item.operations.toMutableList()
                        while (batch.size < MAX_BATCH) {
                            val next = link.outbox.tryReceive().getOrNull() ?: break
                            if (next is OutgoingOperations) batch += next.operations else {
                                held = next
                                break
                            }
                        }
                        val missing = batch.filter { operation ->
                            val id = operation.id
                            (id.sequence > (link.sent[id.replica] ?: 0L)).also { if (it) link.sent[id.replica] = id.sequence }
                        }
                        if (missing.isEmpty()) continue
                        Operations(missing.map { link.seal.seal(it.id.replica, it.id.sequence, SyncCodec.encodeOperation(it)) })
                    }
                    is OutgoingMessage -> item.message
                }
                link.channel.send(SyncCodec.encode(frame))
                if (frame is AttachmentPart) link.attachmentParts.release()
            }
        } catch (_: SyncException) {
            link.channel.close()
        }
    }

    private suspend fun handle(link: Link, message: SyncMessage) {
        // Before the other device is checked: the handshake only
        if (!link.authorized && message !is Challenge && message !is Hello && message !is MembershipMessage &&
            message !is Admission && message !is JoinRefused && message !is Ping
        ) return
        when (message) {
            is Challenge -> sendHello(link, message.nonce)
            is Hello -> onHello(link, message)
            is MembershipMessage -> onMembership(link, message.membership)
            is MembershipQuery -> onMembershipQuery(link, message.version)
            is Admission -> onAdmission(link, message)
            JoinRefused -> onRefused(link)
            is KeyUpdate -> onKeyUpdate(link, message)
            Leave -> onLeave(link)
            is KnownVersions -> onKnownVersions(link, message.versions)
            is Snapshot -> onSnapshot(link, message.snapshot, message.base)
            is Operations -> onOperations(link, message.operations.map { open(link, it) })
            is ResourceRequest -> onResourceRequest(link, message.asks)
            is ResourceChunk -> onResourceChunk(link, message)
            is ResourceMissing -> link.requested.remove(message.id)
            is PresenceMessage -> onPresence(link, message)
            is AttachmentVersions -> onAttachmentVersions(link, message.versions)
            is AttachmentRequest -> message.asks.forEach { link.attachmentRequests.trySend(it) }
            is AttachmentPart -> onAttachmentPart(link, message)
            is DeviceAddresses -> onDeviceAddresses(link, message.addresses)
            Ping -> Unit
        }
    }

    // ==========================================================
    // Who the other device is
    // ==========================================================

    /** Says who this device is, signing the challenge [peerNonce] of the other one. */
    private suspend fun sendHello(link: Link, peerNonce: ByteArray) {
        val replica = replica()
        val state = sharing.stateOf(link.documentId)
        val vector = link.sync?.open?.edit { it.versionVector }
        val proof = identity.sign(helloProof(peerNonce, link.tag, replica, device))
        link.send(
            Hello(
                replica = replica,
                name = deviceName(),
                versionVector = vector.orEmpty(),
                hasDocument = vector != null,
                device = device,
                proof = proof,
                membershipVersion = state?.members?.version ?: 0,
                epoch = state?.epoch ?: 0,
                addresses = own.value.values.toList()
            )
        )
    }

    private suspend fun onHello(link: Link, hello: Hello) {
        if (link.peerHello != null) return
        // Who it says it is, proven: it signed the challenge of this connection
        if (!DeviceIdentity.verify(hello.device, helloProof(link.nonce, link.tag, hello.replica, hello.device), hello.proof)) {
            link.channel.close()
            return
        }
        link.peerHello = hello
        when (val match = link.match) {
            is TagMatch.Grant -> onJoinRequest(link, match.grant, hello)
            // The owner of the invitation, admitting this device or not
            is TagMatch.Joining -> if (DeviceIdentity.fingerprint(hello.device) != match.invitation.owner) link.channel.close() else waitingApproval(link)
            is TagMatch.Member -> {
                val membership = sharing.membershipOf(link.documentId)
                val mine = membership?.version ?: 0
                sharing.stateOf(link.documentId)?.membership?.takeIf { mine > hello.membershipVersion }?.let { link.send(MembershipMessage(it)) }
                if (hello.membershipVersion > mine) link.waitingMembership = true else authorize(link)
            }
        }
    }

    /** The other device of [link] works on the document if it is a member of it (the connection is closed otherwise). */
    private suspend fun authorize(link: Link) {
        val hello = link.peerHello ?: return
        if (link.authorized) return
        val state = sharing.stateOf(link.documentId)
        val membership = state?.members
        if (state == null || membership == null || membership.ended || membership.memberOf(hello.device) == null) {
            link.channel.close()
            return
        }
        link.authorized = true
        // Still on an older key: the current one
        if (hello.epoch < state.epoch) state.key?.let { link.send(KeyUpdate(state.epoch, it.encode())) }
        if (hello.hasDocument) sharing.recordVersions(link.documentId, mapOf(hello.device to hello.versionVector))
        workWith(link, hello)
    }

    /** The other device of [link], a member, works on the document with this one. */
    private suspend fun workWith(link: Link, hello: Hello) {
        val twin = mutex.withLock {
            links.firstOrNull { it !== link && it.documentId == link.documentId && it.authorized && it.peerHello?.replica == hello.replica }
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
        val peer = Peer(link.documentId, hello.replica, hello.name, hello.device)
        link.peer = peer
        connected.update { it + peer }
        exchangeAddresses(link, hello)
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

    /** A more recent membership, from the owner through any device: followed. */
    private suspend fun onMembership(link: Link, signed: SignedMembership) {
        // Not before a device joining is admitted
        if (!link.authorized && !link.waitingMembership) return
        sharing.adopt(link.documentId, signed)?.let { sharing.stateOf(link.documentId)?.let { state -> onMembershipChanged(link.documentId, state, from = link) } }
        if (link.waitingMembership) {
            link.waitingMembership = false
            authorize(link)
        }
        // The operations of the devices admitted meanwhile
        val deferred = link.deferred.toList()
        link.deferred.clear()
        if (deferred.isNotEmpty()) onOperations(link, deferred)
    }

    /** The other device has an older membership than this one: the more recent one. */
    private suspend fun onMembershipQuery(link: Link, version: Long) {
        val state = sharing.stateOf(link.documentId) ?: return
        if ((state.members?.version ?: 0) > version) state.membership?.let { link.send(MembershipMessage(it)) }
    }

    /**
     * The membership of [documentId] changed ([state]): to the other devices, the devices no longer
     * members disconnected, the new key to the members; this device follows it (it may read only,
     * be removed, see the sharing end).
     */
    private suspend fun onMembershipChanged(documentId: String, state: SharingState, from: Link?) {
        val membership = state.members ?: return
        val signed = state.membership ?: return
        val targets = linksOf(documentId).filter { it.authorized && it !== from }
        targets.forEach { it.send(MembershipMessage(signed)) }
        // Who no longer works on it
        for (link in linksOf(documentId)) {
            val device = link.peerHello?.device ?: continue
            if (link.authorized && (membership.memberOf(device) == null || membership.ended)) closeSoon(link)
        }
        // The addresses of the devices no longer members of any document go
        scope.launch { saveAddresses() }
        if (membership.ended || membership.memberOf(device) == null) {
            // This device was removed, or the sharing ended: it keeps its copy
            sharing.forget(documentId)
            linksOf(documentId).forEach { closeSoon(it) }
            return
        }
        // The new key to the members still on the older one (this device the owner, or relaying it)
        state.key?.let { key -> targets.forEach { it.send(KeyUpdate(state.epoch, key.encode())) } }
    }

    /** Closes [link] once its last messages are sent. */
    private fun closeSoon(link: Link) {
        scope.launch {
            delay(CLOSE_DELAY_MS)
            link.channel.close()
        }
    }

    private suspend fun onKeyUpdate(link: Link, update: KeyUpdate) {
        if (!sharing.adoptKey(link.documentId, update.epoch, update.key)) return
        // To the members connected to this device only
        linksOf(link.documentId).filter { it !== link && it.authorized }.forEach { it.send(update) }
    }

    /** A member leaves: the owner removes it. */
    private suspend fun onLeave(link: Link) {
        val hello = link.peerHello ?: return
        if (sharing.stateOf(link.documentId)?.owner == device) removeMember(link.documentId, hello.device)
    }

    // ==========================================================
    // Addresses
    // ==========================================================

    /**
     * Signs where this device can be reached on [transport] now ([address]; `null`: no longer) and
     * gives it to the members connected, who relay it to the others.
     */
    public suspend fun publishAddress(transport: String, address: String?) {
        var signed: DeviceAddress? = null
        own.update { addresses ->
            val previous = addresses[transport]
            if (address == null) return@update addresses - transport
            if (previous?.address == address) return@update addresses
            // Always more recent than the one before, even within the same millisecond
            val issuedAt = maxOf(currentTimeMillis(), (previous?.issuedAt ?: 0) + 1)
            addresses + (transport to DeviceAddress.sign(identity, transport, address, issuedAt).also { signed = it })
        }
        val message = DeviceAddresses(listOf(signed ?: return))
        mutex.withLock { links.filter { it.authorized } }.forEach { it.trySend(message) }
    }

    /** The addresses kept, of the members still. */
    private suspend fun loadAddresses() {
        try {
            val members = memberDevices()
            known.take(addressBook.load(), device) { it in members }
        } finally {
            addressesLoaded.complete(Unit)
        }
    }

    /** The members of the documents shared now. */
    private suspend fun memberDevices(): Set<String> = sharing.all().values
        .mapNotNull { state -> state.members?.takeIf { !it.ended } }
        .flatMapTo(mutableSetOf()) { membership -> membership.members.map { it.device } }

    /** Keeps the addresses known, of the members only. */
    private suspend fun saveAddresses() {
        addressesLoaded.await()
        addressesMutex.withLock {
            val members = memberDevices()
            known.retain { it in members }
            addressBook.save(known.list())
        }
    }

    /**
     * The device of [link] works on the document with this one: its addresses (from its [hello]),
     * and to it the ones of the other members this device knows.
     */
    private suspend fun exchangeAddresses(link: Link, hello: Hello) {
        val membership = sharing.membershipOf(link.documentId) ?: return
        onAddresses(link, hello.addresses.filter { it.device == hello.device }, membership)
        addressesLoaded.await()
        val others = known.of(membership.members.map { it.device }.filter { it != hello.device && it != device })
        if (others.isNotEmpty()) link.send(DeviceAddresses(others))
    }

    private suspend fun onDeviceAddresses(link: Link, addresses: List<DeviceAddress>) {
        val membership = sharing.membershipOf(link.documentId) ?: return
        onAddresses(link, addresses, membership)
    }

    /**
     * Keeps the addresses of [addresses] of members of the document of [from] (see
     * [KnownAddresses.take]), and relays the ones kept to the other members connected (never back).
     */
    private suspend fun onAddresses(from: Link, addresses: List<DeviceAddress>, membership: Membership) {
        if (addresses.isEmpty()) return
        addressesLoaded.await()
        val taken = known.take(addresses, device) { !membership.ended && membership.memberOf(it) != null }
        if (taken.isEmpty()) return
        scope.launch { saveAddresses() }
        for (link in mutex.withLock { links.filter { it !== from && it.authorized } }) {
            val members = sharing.membershipOf(link.documentId) ?: continue
            val relayed = taken.filter { it.device != link.peerHello?.device && members.memberOf(it.device) != null }
            if (relayed.isNotEmpty()) link.trySend(DeviceAddresses(relayed))
        }
    }

    // ==========================================================
    // Joining
    // ==========================================================

    /** A device joins with the invitation [grant] of this device, the owner: admitted, once approved if needed. */
    private suspend fun onJoinRequest(link: Link, grant: InvitationGrant, hello: Hello) {
        val state = sharing.stateOf(link.documentId) ?: return link.channel.close()
        if (state.members?.memberOf(hello.device) != null) {
            admit(link, state, hello)
            return
        }
        if (!grant.approval) {
            sharing.admit(link.documentId, hello.device, hello.replica, hello.name, grant.role)?.let {
                onMembershipChanged(link.documentId, it, from = link)
                admit(link, it, hello)
            }
            return
        }
        // Waits for the owner as long as the device stays connected, without stopping the connection
        val request = JoinRequest(link.documentId, hello.device, hello.name, grant.role)
        link.request = request
        requests.update { it + request }
        scope.launch {
            val approved = request.decision.await()
            requests.update { it - request }
            if (!approved) {
                link.send(JoinRefused)
                closeSoon(link)
                return@launch
            }
            sharing.admit(link.documentId, hello.device, hello.replica, hello.name, grant.role)?.let {
                onMembershipChanged(link.documentId, it, from = link)
                admit(link, it, hello)
            }
        }
    }

    /** Gives the device of [link] the key and the membership of the document, then works with it. */
    private suspend fun admit(link: Link, admitted: SharingState, hello: Hello) {
        // The most recent membership (another device may be admitted at the same time)
        val state = sharing.stateOf(link.documentId) ?: admitted
        val key = state.key ?: return
        val signed = state.membership ?: return
        link.send(Admission(state.epoch, key.encode(), signed))
        link.authorized = true
        workWith(link, hello)
    }

    /** The owner admitted this device: the document follows. */
    private suspend fun onAdmission(link: Link, admission: Admission) {
        if (link.match !is TagMatch.Joining || link.authorized) return
        val owner = link.peerHello?.device ?: return
        sharing.admitted(link.documentId, owner, admission.epoch, admission.key, admission.membership) ?: return link.channel.close()
        joinStates.update { it + (link.documentId to JoinStatus.ADMITTED) }
        link.authorized = true
        workWith(link, link.peerHello ?: return)
    }

    private suspend fun onRefused(link: Link) {
        if (link.match !is TagMatch.Joining) return
        sharing.forget(link.documentId)
        joinStates.update { it + (link.documentId to JoinStatus.REFUSED) }
        link.channel.close()
    }

    /** The joining of the document of [link] waits for the owner to admit this device (or to approve it first). */
    private fun waitingApproval(link: Link) {
        if (link.match is TagMatch.Joining && !link.authorized && link.peerHello != null) {
            joinStates.update { it + (link.documentId to JoinStatus.WAITING_APPROVAL) }
        }
    }

    // ==========================================================
    // Operations
    // ==========================================================

    private suspend fun onSnapshot(link: Link, snapshot: DocumentSnapshot, base: Map<String, Long>) {
        if (link.sync != null || snapshot.id != link.documentId) return
        val sync = adopt(snapshot, base)
        link.sync = sync
        // Kept with the document now that it is here
        sharing.saveAgain(link.documentId)
        link.peerHello?.let { catchUp(link, sync, it) }
    }

    /**
     * Sends the other device what it misses: the document if it does not have it, the operations
     * it has not applied; then the operations as they are made.
     */
    private suspend fun catchUp(link: Link, sync: DocumentSync, hello: Hello) {
        // From now on the operations applied here wait for the link
        sync.open.edit { sync.links.add(link) }
        val known = if (hello.hasDocument) {
            hello.versionVector
        } else {
            // With the operations no longer in the log (in the snapshot): the others follow
            val base = sync.open.compactedOperations()
            link.send(Snapshot(sync.open.edit { it.document.snapshot() }, base))
            base
        }
        sync.open.operationsAfter(known).chunked(MAX_BATCH).forEach { link.outbox.send(OutgoingOperations(it)) }
        sync.open.edit {
            link.caughtUp = true
            for (operation in link.waiting) link.outbox.trySend(OutgoingOperations(listOf(operation)))
            link.waiting.clear()
        }
        requestResources(link, sync)
        // What the members have, for the compaction of the log
        val state = sharing.stateOf(link.documentId)
        link.send(KnownVersions(state?.versions.orEmpty() + (device to sync.open.edit { it.versionVector })))
        // Then the items beside the document
        attachments?.let { link.send(AttachmentVersions(it.versions(link.documentId))) }
    }

    /** The operation of [sealed], opened with the key of [link]: the same as its identity in clear. */
    private fun open(link: Link, sealed: SealedOperation): Operation {
        val operation = SyncCodec.decodeOperation(link.seal.open(sealed))
        if (operation.id.replica != sealed.replica || operation.id.sequence != sealed.sequence) {
            throw SyncException("An operation sealed under another identity")
        }
        return operation
    }

    private suspend fun onOperations(link: Link, operations: List<Operation>) {
        val sync = link.sync ?: return
        // The operations of the devices that may change the document only
        val membership = sharing.membershipOf(link.documentId) ?: return
        // Of a device this one does not know yet (admitted meanwhile): kept until the membership comes
        val (known, unknown) = operations.partition { membership.knows(it.id.replica) }
        if (unknown.isNotEmpty()) {
            link.deferred.addAll(unknown)
            link.send(MembershipQuery(membership.version))
        }
        val accepted = known.filter { membership.mayWrite(it.id.replica, it.id.sequence) }
        if (accepted.isEmpty()) return
        // Prepared first, at the same time (e.g. the strokes modeled again: slow), so that the
        // document waits less
        coroutineScope {
            for (operation in accepted) launch { operation.change.prepare(documents.strokeModeller) }
        }
        sync.open.edit { session ->
            link.pending.addAll(accepted)
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

    /** What the members have: the operations all of them have leave the log. */
    private suspend fun onKnownVersions(link: Link, versions: Map<String, Map<String, Long>>) {
        sharing.recordVersions(link.documentId, versions)
        val sync = link.sync ?: return
        compact(link.documentId, sync)
    }

    /** Removes from the log of [documentId] the operations every member has (known from what they said). */
    private suspend fun compact(documentId: String, sync: DocumentSync) {
        val state = sharing.stateOf(documentId) ?: return
        val membership = state.members ?: return
        val mine = sync.open.edit { it.versionVector }
        val vectors = membership.members.map { member -> if (member.device == device) mine else state.versions[member.device] ?: return }
        val replicas = vectors.flatMap { it.keys }.toSet()
        val base = replicas.associateWith { replica -> vectors.minOf { it[replica] ?: 0L } }.filterValues { it > 0 }
        val done = sync.open.compactedOperations()
        if (base.all { (replica, sequence) -> sequence <= (done[replica] ?: 0L) }) return
        // Every member has all that every other one made (as they said): nothing made without seeing
        // the removals is on its way, what they removed goes for good
        val quiescent = membership.members.zip(vectors).all { (member, vector) -> (base[member.replica] ?: 0L) >= (vector[member.replica] ?: 0L) }
        sync.open.compact(base, purge = quiescent)
    }

    // ==========================================================
    // Files of the resources
    // ==========================================================

    /**
     * Asks the other device for the files of the resources this one misses: the ones it has already
     * (the same content, in another document) are not asked for; a download interrupted goes on.
     */
    private suspend fun requestResources(link: Link, sync: DocumentSync) {
        val missing = sync.open.edit { session -> session.document.resources.filter { it.path.isEmpty() }.map { it.id to it.hash } }
        val asks = mutableListOf<ResourceAsk>()
        for ((id, hash) in missing) {
            documents.fileWithContent(hash)?.takeIf { files.exists(it) }?.let { path ->
                sync.open.resourceReceived(id, path)
                continue
            }
            if (!link.requested.add(id)) continue
            val from = downloadsMutex.withLock { downloads[link.documentId to id]?.received ?: 0 }
            asks.add(ResourceAsk(id, from.toLong()))
        }
        if (asks.isNotEmpty()) link.send(ResourceRequest(asks))
    }

    private suspend fun onResourceRequest(link: Link, asks: List<ResourceAsk>) {
        val sync = link.sync ?: return
        for (ask in asks) {
            val path = sync.open.edit { it.document.findResource(ask.id)?.path }
            val bytes = path?.let { files.read(it) }
            if (path == null || bytes == null) {
                link.send(ResourceMissing(ask.id))
                continue
            }
            val extension = path.substringAfterLast('/').substringAfterLast('.', "")
            var offset = ask.from.coerceIn(0, bytes.size.toLong()).toInt()
            do {
                val end = min(offset + CHUNK_SIZE, bytes.size)
                link.send(ResourceChunk(ask.id, extension, offset.toLong(), bytes.size.toLong(), bytes.copyOfRange(offset, end)))
                offset = end
            } while (offset < bytes.size)
        }
    }

    private suspend fun onResourceChunk(link: Link, chunk: ResourceChunk) {
        val sync = link.sync ?: return
        if (chunk.id !in link.requested || chunk.total !in 0..MAX_RESOURCE_SIZE) return
        val key = link.documentId to chunk.id
        val complete = downloadsMutex.withLock {
            var download = downloads[key]
            if (download == null || download.bytes.size.toLong() != chunk.total || chunk.offset == 0L && download.received != 0) {
                download = Download(ByteArray(chunk.total.toInt())).also { downloads[key] = it }
            }
            // The next bytes only
            if (chunk.offset != download.received.toLong() || chunk.offset + chunk.bytes.size > download.bytes.size) return
            chunk.bytes.copyInto(download.bytes, chunk.offset.toInt())
            download.received += chunk.bytes.size
            if (download.received < download.bytes.size) return
            downloads.remove(key)
            download.bytes
        }
        link.requested.remove(chunk.id)
        val path = files.write(complete, chunk.extension) ?: return
        sync.open.resourceReceived(chunk.id, path)
    }

    // ==========================================================
    // Items beside the documents
    // ==========================================================

    /** The items the other device has: the ones wanted here and different are asked for. */
    private suspend fun onAttachmentVersions(link: Link, versions: Map<String, String>) {
        if (attachments == null || link.sync == null) return
        link.attachmentLock.withLock {
            for ((key, version) in versions) {
                // Asked for at another version: maybe not there anymore
                if (link.offered.put(key, version).let { it != null && it != version }) link.asked.remove(key)
            }
        }
        askForOffered(link)
    }

    /**
     * Asks for the items offered by the other device that are different and wanted here (what is
     * wanted may depend on the items received: asked again after each one), from where an
     * interrupted transfer stopped.
     */
    private suspend fun askForOffered(link: Link) {
        val attachments = attachments ?: return
        val asks = link.attachmentLock.withLock {
            if (link.offered.isEmpty()) return
            val mine = attachments.versions(link.documentId)
            link.offered.entries.removeAll { (key, version) -> mine[key] == version }
            val wanted = link.offered.filter { (key, version) -> key !in link.asked && attachments.wants(link.documentId, key, version) }
            link.asked += wanted.keys
            wanted.map { (key, version) -> AttachmentAsk(key, version, attachments.received(link.documentId, key, version)) }
        }
        if (asks.isNotEmpty()) link.send(AttachmentRequest(asks))
    }

    /** Sends the item asked for by [ask], in parts (from where the other device has it, at the same version). */
    private suspend fun sendAttachment(link: Link, ask: AttachmentAsk) {
        val source = attachments?.open(link.documentId, ask.key) ?: return
        try {
            var offset = if (source.version == ask.version) ask.from.coerceIn(0, source.size) else 0L
            do {
                val bytes = source.read(offset, min(CHUNK_SIZE.toLong(), source.size - offset).toInt()) ?: return
                link.attachmentParts.acquire()
                link.send(AttachmentPart(ask.key, source.version, offset, source.size, bytes))
                offset += bytes.size
            } while (offset < source.size && bytes.isNotEmpty())
        } finally {
            withContext(NonCancellable) { source.close() }
        }
    }

    private suspend fun onAttachmentPart(link: Link, part: AttachmentPart) {
        val attachments = attachments ?: return
        if (link.sync == null || part.size !in 0..MAX_ATTACHMENT_SIZE) return
        var receiving = link.receiving[part.key]
        if (part.offset == 0L || receiving == null) {
            // From the start, or from where an interrupted transfer stopped
            if (part.offset != 0L && part.offset != attachments.received(link.documentId, part.key, part.version)) return
            link.receiving.remove(part.key)?.receiver?.cancel()
            val receiver = attachments.receive(link.documentId, part.key, part.version, part.size, part.offset)
            if (receiver == null) {
                // Not kept here: not asked for again until it changes
                link.attachmentLock.withLock {
                    link.asked.remove(part.key)
                    link.offered.remove(part.key)
                }
                return
            }
            receiving = Receiving(receiver, part.version, part.size).also { it.received = part.offset }
            link.receiving[part.key] = receiving
        }
        if (part.version != receiving.version || part.offset != receiving.received) {
            // Not the next part: the item is asked for again
            link.receiving.remove(part.key)
            link.attachmentLock.withLock { link.asked.remove(part.key) }
            receiving.receiver.cancel()
            return
        }
        receiving.receiver.write(part.bytes)
        receiving.received += part.bytes.size
        if (receiving.received < receiving.size) return
        link.receiving.remove(part.key)
        receiving.receiver.complete()
        link.attachmentLock.withLock {
            link.asked.remove(part.key)
            if (link.offered[part.key] == part.version) link.offered.remove(part.key)
        }
        // Kept otherwise (e.g. merged with the one here): it goes back
        val kept = attachments.version(link.documentId, part.key)
        if (kept != null && kept != part.version) link.send(AttachmentVersions(mapOf(part.key to kept)))
        askForOffered(link)
    }

    /** An item beside the document [AttachmentChange.documentId] changed here: the others are told. */
    private suspend fun onAttachmentChanged(change: AttachmentChange) {
        val targets = mutex.withLock { links.filter { it.documentId == change.documentId && it.authorized && it.sync != null } }
        for (link in targets) link.send(AttachmentVersions(mapOf(change.key to change.version)))
    }

    // ==========================================================
    // Presence
    // ==========================================================

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
            links.filter { it.documentId == presence.documentId && it !== link && it.authorized }
        }
        // A device renamed meanwhile
        link.peer?.takeIf { it.replica == presence.replica && it.name != presence.name }?.let { old ->
            val renamed = old.copy(name = presence.name)
            link.peer = renamed
            connected.update { peers -> peers.map { if (it === old) renamed else it } }
        }
        targets.forEach { it.trySend(message) }
    }

    private companion object {
        /** The most operations in a message. */
        const val MAX_BATCH = 200

        const val CHUNK_SIZE = 256 * 1024

        /** How long a connection lives after its last messages (e.g. telling a device it was removed). */
        const val CLOSE_DELAY_MS = 500L
        const val NONCE_SIZE = 16

        /** How often the whole presence is sent again (see [updatePresence]). */
        const val WHOLE_PRESENCE_MS = 500L
        const val MAX_RESOURCE_SIZE = 512L * 1024 * 1024
        const val MAX_ATTACHMENT_SIZE = 4L * 1024 * 1024 * 1024
        const val ATTACHMENT_PARTS_AHEAD = 4

        /**
         * The largest frame of a device not authorized yet (see [Link.authorized]): its hello, a
         * membership of about a thousand members, an admission.
         */
        const val HANDSHAKE_FRAME_SIZE = 256 * 1024

        /** The incoming channels of devices not known yet at once (see [strangers]). */
        const val MAX_STRANGERS = 16
    }
}
