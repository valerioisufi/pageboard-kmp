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
import io.github.valerioisufi.drawview.model.change.OperationId
import io.github.valerioisufi.drawview.model.change.TextData
import io.github.valerioisufi.drawview.model.change.snapshot
import io.github.valerioisufi.drawview.platform.Sha256
import io.github.valerioisufi.drawview.platform.currentTimeMillis
import io.github.valerioisufi.drawview.store.OpenDocument
import io.github.valerioisufi.drawview.store.OpenDocuments
import io.github.valerioisufi.drawview.store.space.CatalogItemKind
import io.github.valerioisufi.drawview.store.space.CatalogOperation
import io.github.valerioisufi.drawview.store.space.SpaceLibrary
import io.github.valerioisufi.drawview.sync.protocol.DeviceAddress
import io.github.valerioisufi.drawview.sync.protocol.DeviceIdentity
import io.github.valerioisufi.drawview.sync.protocol.DocumentKey
import io.github.valerioisufi.drawview.sync.protocol.Invitation
import io.github.valerioisufi.drawview.sync.protocol.InvitationKind
import io.github.valerioisufi.drawview.sync.protocol.MAX_FRAME_SIZE
import io.github.valerioisufi.drawview.sync.protocol.Member
import io.github.valerioisufi.drawview.sync.protocol.Membership
import io.github.valerioisufi.drawview.sync.protocol.OpenFrame
import io.github.valerioisufi.drawview.sync.protocol.OperationProof
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
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
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

/**
 * Where the root of a shared folder joined goes in the library (see `docs/shared-folders.md`): a
 * new folder at the root named as the folder shared ([NewFolder]), a folder of the library
 * ([Folder]), or the library itself, whose documents and folders then go into the shared folder
 * too ([Library]).
 */
public sealed interface SpaceRoot {
    public data object NewFolder : SpaceRoot

    public data object Library : SpaceRoot

    public data class Folder(val folderId: String) : SpaceRoot
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
 *
 * With [spaces], whole folders and the library are shared too (see `docs/shared-folders.md`): a
 * connection about a shared folder carries the changes of its catalog (signed, as the operations),
 * and its documents are synchronized as the documents shared on their own, with the keys and the
 * membership of the folder.
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
    private val addressBook: AddressBook = InMemoryAddressBook(),
    private val spaces: SpaceLibrary? = null
) {
    private val sharing = DocumentSharing(shared, identity)

    /** The sharing of the shared folders and of their documents (see `docs/shared-folders.md`), when the application keeps spaces. */
    private val spaceSharing = spaces?.let { SpaceSharing(it, identity, sharing) }

    /** Where the root of each space being joined goes, as the user chose (see [joinSpace]). */
    private val spaceRoots = mutableMapOf<String, SpaceRoot>()

    /** One shared folder kept in the library at a time: admitted over two connections, it is kept once. */
    private val spaceJoins = Mutex()

    /** The heads of the documents of each space, as each member connected said them (under [mutex]): space, device, heads. */
    private val remoteHeads = mutableMapOf<String, MutableMap<String, Map<String, String>>>()

    /** The heads of the documents of each space as this device last said them (under [mutex]). */
    private val ownHeads = mutableMapOf<String, Map<String, String>>()

    /** The documents this device works on lately (its presence, see [updatePresence]), with a number changed at each presence. */
    private val workedOn = mutableMapOf<String, Long>()
    private var presenceCount = 0L

    /**
     * The documents of the shared folders to connect about now (see [reachable]): the ones whose
     * heads differ from a member's, missing on either side, or worked on here; the others of a space
     * have no connection of their own (see `docs/shared-folders.md`, phase 4b).
     */
    private val wantedDocuments = MutableStateFlow<Set<String>>(emptySet())
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

    /** The memberships of the shared folders (who works on each one and on its documents, as the owner decided). */
    public val spaceMemberships: StateFlow<Map<String, Membership>> = spaceSharing?.spaces?.memberships ?: MutableStateFlow(emptyMap())

    private val changedFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /** Documents and shared folders whose keys or invitations changed: what to announce on the network (see [reachable]). */
    public val sharingChanged: SharedFlow<String> = changedFlow

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

    /** The files of the resources being received (they go on over another connection). */
    private val downloads = ResourceDownloads(files, MAX_RESOURCE_SIZE)

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
        scope.launch { sharing.changed.collect { changedFlow.emit(it) } }
        spaceSharing?.let { spaceSharing ->
            scope.launch { spaceSharing.spaces.changed.collect { changedFlow.emit(it) } }
            // The changes of the folders made here: to the members connected
            scope.launch { spaces?.localOperations?.collect { onLocalCatalogOperation(it.spaceId, it.operation) } }
            scope.launch { followSpaces() }
            // The documents changed (here or received): their heads told again, once they settle
            scope.launch {
                val changes = merge(
                    documents.store.documents.map { list -> list.map { it.id to it.modifiedAt } }.distinctUntilChanged().map { },
                    attachments?.changes?.map { } ?: emptyFlow()
                )
                @OptIn(FlowPreview::class)
                changes.debounce(HEADS_SETTLE_MS).collect { tellHeads() }
            }
        }
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
        markWorkedOn(presence.documentId)
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
        val access = accessOf(documentId)
        val state = access.stateOf(documentId)
        if (state?.members == null) sharing.share(documentId, replica(), deviceName())
        val grant = access.invite(documentId, role, approval) ?: return null
        return Invitation(documentId, title, DocumentKey.decode(grant.key)!!, DeviceIdentity.fingerprint(device), addresses, kindOf(documentId))
    }

    /** The invitations of the document [documentId] (this device is its owner). */
    public suspend fun invitationsOf(documentId: String): List<InvitationGrant> = accessOf(documentId).stateOf(documentId)?.invitations.orEmpty()

    /** What an invitation to [id] is to: a document, a folder or the library (see `docs/shared-folders.md`). */
    private suspend fun kindOf(id: String): InvitationKind {
        val space = spaces?.spaces()?.firstOrNull { it.id == id } ?: return InvitationKind.DOCUMENT
        return if (space.rootFolderId == null) InvitationKind.LIBRARY else InvitationKind.FOLDER
    }

    /** The invitation [grant] as a link to give, reaching this device at [addresses] (by transport). */
    public fun invitationOf(
        documentId: String,
        title: String,
        grant: InvitationGrant,
        addresses: Map<String, String> = ownAddressesByTransport(),
        kind: InvitationKind = InvitationKind.DOCUMENT
    ): Invitation = Invitation(documentId, title, DocumentKey.decode(grant.key)!!, DeviceIdentity.fingerprint(device), addresses, kind)

    private fun ownAddressesByTransport(): Map<String, String> = own.value.mapValues { it.value.address }

    /** Revokes the invitation [grantId] of [documentId]: nobody joins with it anymore (the devices joined stay). */
    public suspend fun revokeInvitation(documentId: String, grantId: String) {
        accessOf(documentId).revoke(documentId, grantId)
        linksOf(documentId).filter { (it.match as? TagMatch.Grant)?.grant?.id == grantId && !it.authorized }.forEach { it.channel.close() }
    }

    /** Approves (or refuses) the device of [request] joining its document. */
    public fun answer(request: JoinRequest, approve: Boolean) {
        requests.update { it - request }
        request.decision.complete(approve)
    }

    /** Gives the member [target] of [documentId] the role [role] (this device is the owner). */
    public suspend fun setRole(documentId: String, target: String, role: Role) {
        val access = accessOf(documentId)
        val member = access.membershipOf(documentId)?.memberOf(target) ?: return
        val state = access.setRole(documentId, target, role, lastSequenceOf(documentId, member.replica)) ?: return
        onMembershipChanged(documentId, state, from = null, access = access)
    }

    /**
     * Removes the member [target] from [documentId] (this device is the owner): the document gets a
     * new key, given to the other members; the device removed cannot follow it anymore.
     */
    public suspend fun removeMember(documentId: String, target: String) {
        val access = accessOf(documentId)
        val member = access.membershipOf(documentId)?.memberOf(target) ?: return
        val state = access.remove(documentId, target, lastSequenceOf(documentId, member.replica)) ?: return
        onMembershipChanged(documentId, state, from = null, access = access)
    }

    /**
     * Stops sharing the document [documentId]: the owner ends the sharing for every device (each one
     * keeps its copy), another device leaves it (the owner removes it). Its connections are closed.
     */
    public suspend fun unshare(documentId: String) {
        val access = accessOf(documentId)
        val state = access.stateOf(documentId)
        if (state?.owner == device && state.members != null) {
            access.end(documentId)?.let { onMembershipChanged(documentId, it, from = null, access = access) }
        } else {
            linksOf(documentId).filter { it.authorized }.forEach { it.trySend(Leave) }
        }
        if (access === sharing) {
            sharing.forget(documentId)
        } else {
            val inSpace = sharing.all().filterValues { it.space == documentId }.keys
            spaceSharing?.forget(documentId)
            inSpace.forEach { id -> linksOf(id).forEach { closeSoon(it) } }
        }
        joinStates.update { it - documentId }
        // The addresses of the devices no longer members of any document go
        scope.launch { saveAddresses() }
        // The last messages first
        linksOf(documentId).forEach { closeSoon(it) }
    }

    /** The last operation of [replica] on [documentId] this device has. */
    private suspend fun lastSequenceOf(documentId: String, replica: String): Long {
        spaces?.takeIf { it.has(documentId) }?.let { return it.lastApplied(documentId, replica) }
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
    internal suspend fun reachable(announced: Boolean): List<TagMatch> {
        val documents = sharing.reachable(announced)
        if (spaceSharing == null) return documents
        // The documents of a space: when wanted only, and never announced (their space is)
        val inSpaces = sharing.all().filterValues { it.space != null }.keys
        val wanted = wantedDocuments.value
        val kept = documents.filter { it.documentId !in inSpaces || (!announced && it.documentId in wanted) }
        return kept + spaceSharing.spaces.reachable(announced)
    }

    /**
     * The tag the devices sharing [documentId] announce it by: the one of its shared folder for a
     * document of a shared folder (whose documents are not announced each, see [reachable]),
     * `null` otherwise (its own).
     */
    public suspend fun announcedTagOf(documentId: String): String? {
        val space = sharing.stateOf(documentId)?.space ?: return null
        return spaceSharing?.spaces?.stateOf(space)?.key?.tag
    }

    /** The tags this device answers to on the network (see [DocumentKey.tag]). */
    public suspend fun announcedTags(): Set<String> = reachable(announced = true).mapTo(mutableSetOf()) { it.key.tag }

    /** The documents and keys to connect with, and the documents joined (see [connect]). */
    public suspend fun connectionKeys(): List<Pair<String, DocumentKey>> = reachable(announced = false).map { it.documentId to it.key }

    /** Joins the document of [invitation]: the owner admits this device when it reaches it (see [connect]). */
    public suspend fun join(invitation: Invitation) {
        // A shared folder: where its root goes as chosen already (see joinSpace), in a new folder by default
        if (invitation.kind != InvitationKind.DOCUMENT) {
            return joinSpace(invitation, mutex.withLock { spaceRoots[invitation.documentId] } ?: SpaceRoot.NewFolder)
        }
        // Joined already (the invitation opened again): nothing to wait for
        if (memberships.value[invitation.documentId]?.memberOf(device) != null) {
            joinStates.update { it + (invitation.documentId to JoinStatus.ADMITTED) }
            return
        }
        sharing.startJoining(invitation)
        joinStates.update { it + (invitation.documentId to JoinStatus.LOOKING_FOR_OWNER) }
    }

    /**
     * Joins the shared folder of [invitation] (see `docs/shared-folders.md`), its root going where
     * [root] says once the owner admits this device; nothing when the application keeps no spaces.
     */
    public suspend fun joinSpace(invitation: Invitation, root: SpaceRoot) {
        val spaceSharing = spaceSharing ?: return
        // Joined already (the invitation opened again): nothing to wait for, the root stays where it is
        if (spaceMemberships.value[invitation.documentId]?.memberOf(device) != null) {
            joinStates.update { it + (invitation.documentId to JoinStatus.ADMITTED) }
            return
        }
        mutex.withLock { spaceRoots[invitation.documentId] = root }
        spaceSharing.spaces.startJoining(invitation)
        joinStates.update { it + (invitation.documentId to JoinStatus.LOOKING_FOR_OWNER) }
    }

    /**
     * Shares [rootFolderId] (a folder, `null`: the library) as a shared folder, this device its
     * owner (see `docs/shared-folders.md`): its identifier, to invite with ([invite]), or `null` when
     * the application keeps no spaces or the folder is in a space or holds one.
     */
    public suspend fun shareSpace(rootFolderId: String?): String? {
        val library = spaces ?: return null
        val spaceSharing = spaceSharing ?: return null
        val space = library.share(rootFolderId) ?: return null
        spaceSharing.share(space.id, replica(), deviceName())
        return space.id
    }

    /**
     * Gives every document of the spaces kept its sharing from its space (see [SpaceSharing.follow]):
     * once the [SpaceLibrary] is started, the documents still to come among them.
     */
    public suspend fun followSpaces() {
        val library = spaces ?: return
        val spaceSharing = spaceSharing ?: return
        for (space in library.spaces()) spaceSharing.follow(space.id)
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
            val (match, access) = matchOf(open.tag) ?: return@launch
            // A device joins through the owner only
            if (match is TagMatch.Joining) return@launch
            val secure = SecureChannel.of(channel, match.key)
            run(Link(secure, match, opened = false, access), if (access === sharing) acquire(match.documentId) else null, known)
        } catch (_: SyncException) {
            // Refused or lost before it started
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // What it sent made the start fail (see run)
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
            val (match, access) = matchOf(key.tag)?.takeIf { it.first.documentId == documentId } ?: return@launch
            channel.send(OpenFrame(PROTOCOL_VERSION, key.tag).encode())
            val secure = SecureChannel.of(channel, key)
            run(Link(secure, match, opened = true, access), if (access === sharing) acquire(documentId) else null)
        } catch (_: SyncException) {
            // Refused or lost before it started
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // What it sent made the start fail (see run)
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

    /** What the connection of [tag] is about, and the sharing it is decided by: a document's, or a shared folder's. */
    private suspend fun matchOf(tag: String): Pair<TagMatch, DocumentSharing>? {
        sharing.match(tag)?.let { return it to sharing }
        val spaces = spaceSharing?.spaces ?: return null
        return spaces.match(tag)?.let { it to spaces }
    }

    /** The sharing [id] is decided by: of a shared folder when it is one, of a document otherwise. */
    private suspend fun accessOf(id: String): DocumentSharing =
        spaceSharing?.spaces?.takeIf { it.stateOf(id) != null } ?: sharing

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
            tellLater()
        }

        /** Says again what this device has, once the changes stop for [QUIET_MS] (see [tellVersions]). */
        var telling: Job? = null

        /** Starts again the wait before saying what this device has (on the thread of the document). */
        fun tellLater() {
            telling?.cancel()
            telling = scope.launch {
                delay(QUIET_MS)
                tellVersions(this@DocumentSync)
            }
        }

        /**
         * The proofs of the last operations signed here or received (under [proofsLock]): the
         * others are read from the store.
         */
        private val recentProofs = LinkedHashMap<OperationId, OperationProof>()
        private val proofsLock = Mutex()

        suspend fun remember(proofs: Map<OperationId, OperationProof>) = proofsLock.withLock {
            recentProofs.putAll(proofs)
            while (recentProofs.size > RECENT_PROOFS) recentProofs.remove(recentProofs.keys.first())
        }

        /** The proofs of the operations [ids] of the other devices (the ones without one left out). */
        suspend fun proofsOf(ids: List<OperationId>): Map<OperationId, OperationProof> {
            val found = proofsLock.withLock { ids.mapNotNull { id -> recentProofs[id]?.let { id to it } }.toMap() }
            val missing = ids.filter { it !in found }
            if (missing.isEmpty()) return found
            return found + open.proofs(missing).mapNotNull { (id, bytes) -> OperationProof.decode(bytes)?.let { id to it } }
        }

        /** The proof of [operation], made by this device: signed now (once). */
        suspend fun ownProof(operation: Operation): OperationProof {
            proofsLock.withLock { recentProofs[operation.id] }?.let { return it }
            val id = operation.id
            val proof = OperationProof.sign(identity, open.document.id, id.replica, id.sequence, SyncCodec.encodeOperation(operation))
            remember(mapOf(id to proof))
            return proof
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
        val open = documents.open(snapshot.id) ?: run {
            // A document of a shared folder goes where the folder has it
            val placed = spaces?.folderOf(snapshot.id)
            val folder = if (placed != null) placed.getOrNull() else receivedFolder()
            documents.insert(snapshot.toDocument(documents.strokeModeller), folder, base)
        }
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
        sync.open.edit {
            it.removeObserver(sync.observer)
            sync.telling?.cancel()
        }
        documents.close(sync.open)
    }

    private suspend fun linksOf(documentId: String): List<Link> = mutex.withLock { links.filter { it.documentId == documentId } }

    /**
     * Says what this device has of the document of [sync] (its version vector) on its connections
     * caught up that were told another one: the members staying connected compact their logs (see
     * [compact]) without waiting to connect again.
     */
    private suspend fun tellVersions(sync: DocumentSync) {
        sync.open.edit { session ->
            val vector = session.versionVector
            for (link in sync.links) {
                if (!link.caughtUp || link.toldVersions == vector) continue
                link.toldVersions = vector
                link.trySend(KnownVersions(mapOf(device to vector)))
            }
        }
    }

    // ==========================================================
    // Connections
    // ==========================================================

    /** A connection about the document of [match] (a key of it, or an invitation), [opened] by this device or by the other one. */
    private inner class Link(val channel: SyncChannel, val match: TagMatch, val opened: Boolean, val access: DocumentSharing = sharing) {
        val documentId: String get() = match.documentId

        /** A connection about a shared folder ([documentId] its space): its catalog travels, not a document. */
        val space: Boolean get() = access !== sharing

        /** The changes of the catalog received and not applied yet (waiting for the ones before, or for the membership). */
        val catalogPending = mutableListOf<Pair<CatalogOperation, OperationProof>>()

        /** The last change of every replica of the catalog the other device has (sent, or it had them). */
        val catalogSent = mutableMapOf<String, Long>()

        /** The changes of the catalog made here are sent as they are made (the missed ones were sent). */
        var catalogCaughtUp = false

        /** Sends the changes of the catalog in order (the ones missed, then the ones made). */
        val catalogLock = Mutex()

        /** The messages but [Ping]s received (see [scheduleIdleClose]). */
        var activity = 0L

        /** Closes this connection about a document of a shared folder once idle (see [scheduleIdleClose]). */
        var idleCheck: Job? = null

        /** The connection is over (see [detach]). */
        var closed = false
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

        /** What this device last said it has of the document on this connection (see [tellVersions]). */
        var toldVersions: Map<String, Long>? = null
        val waiting = mutableListOf<Operation>()

        /** Operations received before the ones they follow. */
        val pending = mutableListOf<Operation>()

        /** Operations of devices not in the membership known here (on the receiving coroutine): applied once it comes. */
        val deferred = mutableListOf<Received>()

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

    /** An operation received with the proof of its author (checked once the membership names it). */
    private class Received(val operation: Operation, val proof: OperationProof)

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
        val transfers = attachments?.let {
            scope.launch {
                try {
                    for (ask in link.attachmentRequests) sendAttachment(link, ask)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Asked for what cannot be sent: the connection closes (see below)
                    link.channel.close()
                }
            }
        }
        try {
            if (link.match is TagMatch.Joining) joinStates.update { it + (link.documentId to JoinStatus.LOOKING_FOR_OWNER) }
            link.send(Challenge(link.nonce))
            while (true) {
                // Closed, or silent for too long: lost. Small frames until the other device is authorized
                val frame = receiveLive(link.channel, if (link.authorized) MAX_FRAME_SIZE else HANDSHAKE_FRAME_SIZE) ?: break
                known()
                link.receivedAt = currentTimeMillis()
                val message = SyncCodec.decode(frame)
                if (message != null && message != Ping) link.activity++
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
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // What the other device sent made its handling fail: its connection closes, this device
            // and its other connections go on (what another device sends is untrusted)
        } finally {
            sender.cancel()
            transfers?.cancel()
            link.channel.close()
            withContext(NonCancellable) { detach(link) }
        }
    }

    private suspend fun detach(link: Link) {
        link.closed = true
        link.idleCheck?.cancel()
        // A member no longer connected about a space: its heads count no more
        if (link.space && link.authorized) scope.launch { recomputeWanted() }
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
                        val sealed = seal(link, missing)
                        if (sealed.isEmpty()) continue
                        Operations(sealed)
                    }
                    is OutgoingMessage -> item.message
                }
                link.channel.send(SyncCodec.encode(frame))
                if (frame is AttachmentPart) link.attachmentParts.release()
            }
        } catch (_: SyncException) {
            link.channel.close()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Asked for what cannot be sent (see run)
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
            is Operations -> if (!link.space) onOperations(link, message.operations.map { open(link, it) })
            is CatalogOperations -> onCatalogOperations(link, message.operations)
            is DocumentHeads -> onDocumentHeads(link, message.heads)
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
        val state = link.access.stateOf(link.documentId)
        val vector = if (link.space) {
            spaces?.takeIf { it.has(link.documentId) }?.versionVector(link.documentId)
        } else {
            link.sync?.open?.edit { it.versionVector }
        }
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
                val membership = link.access.membershipOf(link.documentId)
                val mine = membership?.version ?: 0
                link.access.stateOf(link.documentId)?.membership?.takeIf { mine > hello.membershipVersion }?.let { link.send(MembershipMessage(it)) }
                if (hello.membershipVersion > mine) link.waitingMembership = true else authorize(link)
            }
        }
    }

    /** The other device of [link] works on the document if it is a member of it (the connection is closed otherwise). */
    private suspend fun authorize(link: Link) {
        val hello = link.peerHello ?: return
        if (link.authorized) return
        val state = link.access.stateOf(link.documentId)
        val membership = state?.members
        if (state == null || membership == null || membership.ended || membership.memberOf(hello.device) == null) {
            link.channel.close()
            return
        }
        link.authorized = true
        // Still on an older key: the current one
        if (hello.epoch < state.epoch) state.key?.let { link.send(KeyUpdate(state.epoch, it.encode())) }
        if (hello.hasDocument && !link.space) sharing.recordVersions(link.documentId, mapOf(hello.device to hello.versionVector))
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
        if (link.space) {
            catchUpSpace(link, hello)
            return
        }
        // A document of a shared folder: closed once idle and no longer wanted
        if (sharing.stateOf(link.documentId)?.space != null) scheduleIdleClose(link)
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
        link.access.adopt(link.documentId, signed)?.let {
            link.access.stateOf(link.documentId)?.let { state -> onMembershipChanged(link.documentId, state, from = link, access = link.access) }
        }
        if (link.waitingMembership) {
            link.waitingMembership = false
            authorize(link)
        }
        // The changes of the catalog of the devices admitted meanwhile
        if (link.space) applyCatalog(link)
        // The operations of the devices admitted meanwhile
        val deferred = link.deferred.toList()
        link.deferred.clear()
        if (deferred.isNotEmpty()) onOperations(link, deferred)
    }

    /** The other device has an older membership than this one: the more recent one. */
    private suspend fun onMembershipQuery(link: Link, version: Long) {
        val state = link.access.stateOf(link.documentId) ?: return
        if ((state.members?.version ?: 0) > version) state.membership?.let { link.send(MembershipMessage(it)) }
    }

    /**
     * The membership of [documentId] changed ([state]): to the other devices, the devices no longer
     * members disconnected, the new key to the members; this device follows it (it may read only,
     * be removed, see the sharing end).
     */
    private suspend fun onMembershipChanged(documentId: String, state: SharingState, from: Link?, access: DocumentSharing = sharing) {
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
            if (access === sharing) {
                sharing.forget(documentId)
            } else {
                // A shared folder: its documents too, each device keeping its copies
                val inSpace = sharing.all().filterValues { it.space == documentId }.keys
                spaceSharing?.forget(documentId)
                inSpace.forEach { id -> linksOf(id).forEach { closeSoon(it) } }
            }
            linksOf(documentId).forEach { closeSoon(it) }
            return
        }
        // The new key to the members still on the older one (this device the owner, or relaying it)
        state.key?.let { key -> targets.forEach { it.send(KeyUpdate(state.epoch, key.encode())) } }
        // A shared folder: its documents follow it, their connections told
        if (access !== sharing) followSpace(documentId)
    }

    /**
     * The documents of the space [spaceId] take its sharing again (its membership or its keys
     * changed, or what it holds): their connections are told the membership, and the devices no
     * longer members disconnected.
     */
    private suspend fun followSpace(spaceId: String) {
        val spaceSharing = spaceSharing ?: return
        val before = sharing.all().filterValues { it.space == spaceId }
        spaceSharing.follow(spaceId)
        val after = sharing.all().filterValues { it.space == spaceId }
        for ((documentId, state) in after) {
            if (before[documentId]?.membership?.bytes?.contentEquals(state.membership?.bytes) == true) continue
            onMembershipChanged(documentId, state, from = null)
        }
        // Out of the space: shared through it no more
        for (documentId in before.keys - after.keys) linksOf(documentId).forEach { closeSoon(it) }
    }

    /** Closes [link] once its last messages are sent. */
    private fun closeSoon(link: Link) {
        scope.launch {
            delay(CLOSE_DELAY_MS)
            link.channel.close()
        }
    }

    private suspend fun onKeyUpdate(link: Link, update: KeyUpdate) {
        if (!link.access.adoptKey(link.documentId, update.epoch, update.key)) return
        // A new key of a shared folder: of its documents too
        if (link.space) followSpace(link.documentId)
        // To the members connected to this device only
        linksOf(link.documentId).filter { it !== link && it.authorized }.forEach { it.send(update) }
    }

    /** A member leaves: the owner removes it. */
    private suspend fun onLeave(link: Link) {
        val hello = link.peerHello ?: return
        if (link.access.stateOf(link.documentId)?.owner == device) removeMember(link.documentId, hello.device)
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
    private suspend fun memberDevices(): Set<String> {
        // The memberships checked once each (see DocumentSharing.memberships), not their signatures again
        sharing.all()
        spaceSharing?.spaces?.all()
        return (sharing.memberships.value.values + spaceSharing?.spaces?.memberships?.value?.values.orEmpty())
            .filter { !it.ended }
            .flatMapTo(mutableSetOf()) { membership -> membership.members.map { it.device } }
    }

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
        val membership = link.access.membershipOf(link.documentId) ?: return
        onAddresses(link, hello.addresses.filter { it.device == hello.device }, membership)
        addressesLoaded.await()
        val others = known.of(membership.members.map { it.device }.filter { it != hello.device && it != device })
        if (others.isNotEmpty()) link.send(DeviceAddresses(others))
    }

    private suspend fun onDeviceAddresses(link: Link, addresses: List<DeviceAddress>) {
        val membership = link.access.membershipOf(link.documentId) ?: return
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
            val members = link.access.membershipOf(link.documentId) ?: continue
            val relayed = taken.filter { it.device != link.peerHello?.device && members.memberOf(it.device) != null }
            if (relayed.isNotEmpty()) link.trySend(DeviceAddresses(relayed))
        }
    }

    // ==========================================================
    // Joining
    // ==========================================================

    /** A device joins with the invitation [grant] of this device, the owner: admitted, once approved if needed. */
    private suspend fun onJoinRequest(link: Link, grant: InvitationGrant, hello: Hello) {
        val state = link.access.stateOf(link.documentId) ?: return link.channel.close()
        if (state.members?.memberOf(hello.device) != null) {
            admit(link, state, hello)
            return
        }
        if (!grant.approval) {
            link.access.admit(link.documentId, hello.device, hello.replica, hello.name, grant.role)?.let {
                onMembershipChanged(link.documentId, it, from = link, access = link.access)
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
            link.access.admit(link.documentId, hello.device, hello.replica, hello.name, grant.role)?.let {
                onMembershipChanged(link.documentId, it, from = link, access = link.access)
                admit(link, it, hello)
            }
        }
    }

    /** Gives the device of [link] the key and the membership of the document, then works with it. */
    private suspend fun admit(link: Link, admitted: SharingState, hello: Hello) {
        // The most recent membership (another device may be admitted at the same time)
        val state = link.access.stateOf(link.documentId) ?: admitted
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
        val membership = link.access.admitted(link.documentId, owner, admission.epoch, admission.key, admission.membership) ?: return link.channel.close()
        // A shared folder: kept in the library where the user chose
        if (link.space) joinLibrary(link.documentId, (link.match as TagMatch.Joining).invitation, membership.memberOf(device)?.role == Role.READER)
        joinStates.update { it + (link.documentId to JoinStatus.ADMITTED) }
        link.authorized = true
        workWith(link, link.peerHello ?: return)
    }

    private suspend fun onRefused(link: Link) {
        if (link.match !is TagMatch.Joining) return
        link.access.forget(link.documentId)
        joinStates.update { it + (link.documentId to JoinStatus.REFUSED) }
        link.channel.close()
    }

    /** The joining of the document of [link] waits for the owner to admit this device (or to approve it first). */
    private fun waitingApproval(link: Link) {
        if (link.match is TagMatch.Joining && !link.authorized && link.peerHello != null) {
            joinStates.update { it + (link.documentId to JoinStatus.WAITING_APPROVAL) }
        }
    }

    /**
     * Whether [giver] may give the document [documentId] to a device without it: its owner, or, in
     * a shared folder, the device that brought it into the folder (see [SpaceSharing.mayGive]).
     */
    private suspend fun mayGive(documentId: String, giver: String): Boolean {
        val state = sharing.stateOf(documentId) ?: return false
        val space = state.space ?: return giver == state.owner
        return spaceSharing?.mayGive(space, documentId, giver) ?: false
    }

    /** The shared folder [spaceId] admitted this device: kept in the library where the user chose to put it. */
    private suspend fun joinLibrary(spaceId: String, invitation: Invitation, readOnly: Boolean) = spaceJoins.withLock {
        val library = spaces ?: return@withLock
        // Admitted again over another connection (both devices reaching each other): kept already
        if (library.has(spaceId)) return@withLock
        val root = mutex.withLock { spaceRoots.remove(spaceId) } ?: SpaceRoot.NewFolder
        val rootFolder = when (root) {
            SpaceRoot.Library -> null
            is SpaceRoot.Folder -> root.folderId
            SpaceRoot.NewFolder -> library.newRootFolder(invitation.title)
        }
        library.join(spaceId, rootFolder, readOnly)
        // The sharing waiting for the space, kept with it now that it is in the library
        spaceSharing?.spaces?.saveAgain(spaceId)
    }

    /** Sends the other device of [link], about a shared folder, the changes of its catalog it misses; then the ones made as they are made. */
    private suspend fun catchUpSpace(link: Link, hello: Hello) {
        val library = spaces ?: return
        // Joining: the catalog comes from the other device
        if (!library.has(link.documentId)) return
        link.catalogLock.withLock {
            if (hello.hasDocument) link.catalogSent.putAll(hello.versionVector)
            library.operationsAfter(link.documentId, link.catalogSent).chunked(MAX_BATCH).forEach { sendCatalog(link, it) }
            link.catalogCaughtUp = true
        }
        // What each document is at here: the other device connects about the ones that differ
        link.send(DocumentHeads(headsOf(link.documentId)))
    }

    /**
     * The heads of the documents of [spaceId] this device has: a hash of the version vector of each
     * one and of the versions of its items (the lectures...), the same on two devices with the same
     * document.
     */
    private suspend fun headsOf(spaceId: String): Map<String, String> {
        val library = spaces ?: return emptyMap()
        val store = documents.store
        val here = store.documents.first().mapTo(HashSet()) { it.id } + store.trash.first().flatMap { it.documentIds }
        return library.view(spaceId).filter { it.kind == CatalogItemKind.DOCUMENT && it.id in here }.associate { entry ->
            val vector = store.versionVector(entry.id).entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }
            val items = attachments?.versions(entry.id).orEmpty().entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
            entry.id to Sha256.of("$vector|$items".encodeToByteArray())
        }
    }

    /** Tells the members connected about each space the heads of its documents, when they changed. */
    private suspend fun tellHeads() {
        val library = spaces ?: return
        for (space in library.spaces()) {
            val heads = headsOf(space.id)
            val changed = mutex.withLock { (ownHeads[space.id] != heads).also { ownHeads[space.id] = heads } }
            if (changed) linksOf(space.id).filter { it.space && it.authorized && it.catalogCaughtUp }.forEach { it.trySend(DocumentHeads(heads)) }
        }
        recomputeWanted()
    }

    /** The heads of the documents of the space of [link], as its other device has them. */
    private suspend fun onDocumentHeads(link: Link, heads: Map<String, String>) {
        if (!link.space) return
        val device = link.peerHello?.device ?: return
        if (heads.size > MAX_HEADS) throw SyncException("Too many heads")
        mutex.withLock { remoteHeads.getOrPut(link.documentId) { mutableMapOf() }[device] = heads }
        recomputeWanted()
    }

    /** This device works on [documentId]: wanted for a while (the others see its presence). */
    private suspend fun markWorkedOn(documentId: String) {
        if (spaceSharing == null) return
        val (first, count) = mutex.withLock {
            val count = ++presenceCount
            (workedOn.put(documentId, count) == null) to count
        }
        if (first) recomputeWanted()
        // No longer wanted once it is left alone for a while (no presence since this one)
        scope.launch {
            delay(WORKED_ON_MS)
            val left = mutex.withLock { (workedOn[documentId] == count).also { if (it) workedOn.remove(documentId) } }
            if (left) recomputeWanted()
        }
    }

    /**
     * The documents of the spaces to connect about (see [wantedDocuments]): the ones whose heads
     * differ from the ones of a member connected about their space (or that one of the two has
     * not), the ones worked on here lately; the others tell the transports when it changes.
     */
    private suspend fun recomputeWanted() {
        val library = spaces ?: return
        val wanted = HashSet<String>()
        for (space in library.spaces()) {
            val mine = headsOf(space.id)
            val peers = mutex.withLock {
                val connected = links.filter { it.space && it.authorized && it.documentId == space.id }.mapNotNullTo(HashSet()) { it.peerHello?.device }
                remoteHeads[space.id].orEmpty().filterKeys { it in connected }
            }
            val inSpace = library.view(space.id).filter { it.kind == CatalogItemKind.DOCUMENT }.map { it.id }
            for (heads in peers.values) {
                for (documentId in inSpace) {
                    val theirs = heads[documentId]
                    val ours = mine[documentId]
                    if ((theirs != null || ours != null) && theirs != ours) wanted += documentId
                }
            }
        }
        mutex.withLock { wanted += workedOn.keys }
        val inSpaces = sharing.all().filterValues { it.space != null }.keys
        wanted.retainAll(inSpaces)
        val before = wantedDocuments.value
        if (wanted == before) return
        wantedDocuments.value = wanted
        // The transports connect about the documents wanted now
        (wanted - before).forEach { changedFlow.tryEmit(it) }
        // The connections of the ones no longer wanted close once idle
        for (documentId in before - wanted) linksOf(documentId).forEach { scheduleIdleClose(it) }
    }

    /**
     * Closes the connection [link] about a document of a shared folder once it has been idle (no
     * message but [Ping]) for [IDLE_CLOSE_MS] and its document is not wanted (see
     * [wantedDocuments]); its next changes open another one.
     */
    private fun scheduleIdleClose(link: Link) {
        if (link.idleCheck?.isActive == true) return
        link.idleCheck = scope.launch {
            while (true) {
                val seen = link.activity
                delay(IDLE_CLOSE_MS)
                if (link.closed) return@launch
                val state = sharing.stateOf(link.documentId) ?: return@launch
                if (state.space == null || link.documentId in wantedDocuments.value) return@launch
                // Nothing but pings came meanwhile: idle
                if (link.activity == seen) {
                    link.channel.close()
                    return@launch
                }
            }
        }
    }

    /** A change of the catalog of [spaceId] made here: to the members connected about the folder. */
    private suspend fun onLocalCatalogOperation(spaceId: String, operation: CatalogOperation) {
        // A document added or taken out: shared through the space or not
        followSpace(spaceId)
        for (link in linksOf(spaceId).filter { it.space && it.authorized }) {
            link.catalogLock.withLock { if (link.catalogCaughtUp) sendCatalog(link, listOf(operation)) }
        }
    }

    /**
     * [operations] of the catalog of the space of [link] sealed for it with the proofs of their
     * authors (this device signs its own, the ones of the others go as they came), the ones the
     * other device has not got only.
     */
    private suspend fun sendCatalog(link: Link, operations: List<CatalogOperation>) {
        val library = spaces ?: return
        val spaceId = link.documentId
        val missing = operations.filter { operation ->
            val id = operation.id
            (id.sequence > (link.catalogSent[id.replica] ?: 0L)).also { if (it) link.catalogSent[id.replica] = id.sequence }
        }
        if (missing.isEmpty()) return
        val mine = replica()
        val proofs = library.proofs(spaceId, missing.filter { it.id.replica != mine }.map { it.id })
        val sealed = missing.mapNotNull { operation ->
            val id = operation.id
            val proof = if (id.replica == mine) {
                OperationProof.signCatalog(identity, spaceId, id.replica, id.sequence, SyncCodec.encodeCatalogOperation(operation))
            } else {
                proofs[id]?.let(OperationProof::decode)
            }
            proof?.let { link.seal.seal(id.replica, id.sequence, it) }
        }
        if (sealed.isNotEmpty()) link.send(CatalogOperations(sealed))
    }

    /** Changes of the catalog of the space of [link], opened with its key: the same as their identities in clear. */
    private suspend fun onCatalogOperations(link: Link, sealed: List<SealedOperation>) {
        if (!link.space || spaces?.has(link.documentId) != true) return
        for (item in sealed) {
            val proof = link.seal.open(item)
            val operation = SyncCodec.decodeCatalogOperation(proof.content)
            if (operation.id.replica != item.replica || operation.id.sequence != item.sequence) {
                throw SyncException("A change of a folder sealed under another identity")
            }
            link.catalogPending += operation to proof
        }
        if (link.catalogPending.size > MAX_PENDING_CATALOG) throw SyncException("Too many changes of a folder waiting")
        applyCatalog(link)
    }

    /**
     * Applies the changes of the catalog of [link] that follow the ones applied (of the members
     * known: the others wait for the membership), checked first: signed by the device of their
     * replica as its role allows, and admitted by the catalog (stamps, bounds). One that is not
     * closes the connection, before anything of the batch is applied.
     */
    private suspend fun applyCatalog(link: Link) {
        val library = spaces ?: return
        val spaceSharing = spaceSharing ?: return
        val spaceId = link.documentId
        val membership = link.access.membershipOf(spaceId) ?: return
        val next = mutableMapOf<String, Long>()
        val batch = mutableListOf<Pair<CatalogOperation, OperationProof>>()
        var progress = true
        while (progress) {
            progress = false
            val iterator = link.catalogPending.iterator()
            while (iterator.hasNext()) {
                val item = iterator.next()
                val id = item.first.id
                if (!membership.knows(id.replica)) continue
                val last = next.getOrPut(id.replica) { library.lastApplied(spaceId, id.replica) }
                when {
                    id.sequence <= last -> iterator.remove()
                    id.sequence == last + 1 -> {
                        if (!spaceSharing.admitsChange(spaceId, item.first, item.second) || !library.admits(spaceId, item.first)) {
                            throw SyncException("A change of a folder not admitted")
                        }
                        batch += item
                        next[id.replica] = id.sequence
                        iterator.remove()
                        progress = true
                    }
                }
            }
        }
        if (batch.isEmpty()) return
        library.saveProofs(spaceId, batch.associate { it.first.id to it.second.encode() })
        val operations = batch.map { it.first }
        library.receive(spaceId, operations)
        followSpace(spaceId)
        // To the other members connected about the folder (never back)
        for (other in linksOf(spaceId).filter { it !== link && it.space && it.authorized }) {
            other.catalogLock.withLock { if (other.catalogCaughtUp) sendCatalog(other, operations) }
        }
    }

    // ==========================================================
    // Operations
    // ==========================================================

    private suspend fun onSnapshot(link: Link, snapshot: DocumentSnapshot, base: Map<String, Long>) {
        if (link.sync != null || snapshot.id != link.documentId) return
        // From its owner only (a member could give anything), or, in a shared folder, from the device that brought it
        val giver = link.peerHello?.device ?: return
        if (!mayGive(link.documentId, giver)) return
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
        // A device without the document gets it from its owner (see onSnapshot)
        if (!hello.hasDocument && !mayGive(link.documentId, device)) return
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
        // What this device has, for the compaction of the log (each device says its own); said again
        // as it changes (see tellVersions)
        val vector = sync.open.edit { session -> session.versionVector.also { link.toldVersions = it } }
        link.send(KnownVersions(mapOf(device to vector)))
        // Then the items beside the document
        attachments?.let { link.send(AttachmentVersions(it.versions(link.documentId))) }
    }

    /**
     * [operations] sealed for [link] with the proofs of their authors: this device signs its own,
     * the ones of the others go as they came (an operation of another device without one is left
     * to its author).
     */
    private suspend fun seal(link: Link, operations: List<Operation>): List<SealedOperation> {
        val sync = link.sync ?: return emptyList()
        val mine = replica()
        val proofs = sync.proofsOf(operations.filter { it.id.replica != mine }.map { it.id })
        return operations.mapNotNull { operation ->
            val id = operation.id
            val proof = if (id.replica == mine) sync.ownProof(operation) else proofs[id]
            proof?.let { link.seal.seal(id.replica, id.sequence, it) }
        }
    }

    /** The operation of [sealed], opened with the key of [link]: the same as its identity in clear. */
    private fun open(link: Link, sealed: SealedOperation): Received {
        val proof = link.seal.open(sealed)
        val operation = SyncCodec.decodeOperation(proof.content)
        if (operation.id.replica != sealed.replica || operation.id.sequence != sealed.sequence) {
            throw SyncException("An operation sealed under another identity")
        }
        return Received(operation, proof)
    }

    private suspend fun onOperations(link: Link, operations: List<Received>) {
        val sync = link.sync ?: return
        // The operations of the devices that may change the document only
        val membership = sharing.membershipOf(link.documentId) ?: return
        // Of a device this one does not know yet (admitted meanwhile): kept until the membership comes
        val (known, unknown) = operations.partition { membership.knows(it.operation.id.replica) }
        if (unknown.isNotEmpty()) {
            link.deferred.addAll(unknown)
            link.send(MembershipQuery(membership.version))
        }
        // Signed by the device of its replica, wherever it comes from: the connection carrying one
        // that is not is closed
        for (received in known) {
            val id = received.operation.id
            val author = membership.deviceOf(id.replica)
            if (author == null || !received.proof.isBy(author, link.documentId, id.replica, id.sequence)) {
                throw SyncException("An operation not signed by the device of ${id.replica}")
            }
        }
        val allowed = known.filter { membership.mayWrite(it.operation.id.replica, it.operation.id.sequence) }
        if (allowed.isEmpty()) return
        // Stamped by its replica, at most an hour ahead of the clock here, carrying no stamp after
        // its own: the connection carrying one that is not is closed, before anything of it is kept
        val refused = sync.open.edit { session -> allowed.firstOrNull { !session.admits(it.operation) } }
        if (refused != null) {
            val id = refused.operation.id
            throw SyncException("An operation ${id.replica}/${id.sequence} stamped ${refused.operation.stamp}, ahead or carrying stamps after its own")
        }
        // Kept to give them on, as they came
        val proofs = allowed.filter { it.operation.id.replica != replica() }.associate { it.operation.id to it.proof }
        if (proofs.isNotEmpty()) {
            sync.remember(proofs)
            sync.open.saveProofs(proofs.mapValues { it.value.encode() })
        }
        val accepted = allowed.map { it.operation }
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

    /**
     * What the other device has: the operations all the members have leave the log. What it says of
     * the others is not taken: they say it themselves (a member could make the others drop what
     * one of them misses).
     */
    private suspend fun onKnownVersions(link: Link, versions: Map<String, Map<String, Long>>) {
        val sender = link.peerHello?.device ?: return
        versions[sender]?.let { sharing.recordVersions(link.documentId, mapOf(sender to it)) }
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
            asks.add(ResourceAsk(id, downloads.receivedOf(link.documentId, id)))
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
            // The name of the file, whatever the separators of the paths of this device
            val extension = path.substringAfterLast('/').substringAfterLast('\\').substringAfterLast('.', "")
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
        if (chunk.id !in link.requested) return
        val whole = downloads.add(link.documentId, chunk) { sync.open.edit { it.document.findResource(chunk.id)?.hash } } ?: return
        link.requested.remove(chunk.id)
        // Another file than the one its operation names is not kept, and asked for again at the next connection
        sync.open.resourceReceived(chunk.id, whole.path ?: return)
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

        /** The changes of a folder waiting for the ones before them on a connection, at most (a device sending only later ones fills nothing). */
        const val MAX_PENDING_CATALOG = 20_000

        /** How long the changes of the documents settle before their heads are told again. */
        const val HEADS_SETTLE_MS = 2_000L

        /** How long a document worked on here stays wanted after its last presence. */
        const val WORKED_ON_MS = 30_000L

        /** How long a connection about a document of a shared folder no longer wanted stays open idle. */
        const val IDLE_CLOSE_MS = 30_000L

        /** The heads of a space taken from another device, at most. */
        const val MAX_HEADS = 100_000

        const val CHUNK_SIZE = 256 * 1024

        /** How long a connection lives after its last messages (e.g. telling a device it was removed). */
        const val CLOSE_DELAY_MS = 500L
        const val NONCE_SIZE = 16

        /** How often the whole presence is sent again (see [updatePresence]). */
        const val WHOLE_PRESENCE_MS = 500L
        const val MAX_RESOURCE_SIZE = 512L * 1024 * 1024
        const val MAX_ATTACHMENT_SIZE = 4L * 1024 * 1024 * 1024
        const val ATTACHMENT_PARTS_AHEAD = 4

        /** The proofs of the last operations kept in memory by document (see [DocumentSync.proofsOf]). */
        const val RECENT_PROOFS = 1024

        /**
         * How long the changes of a document stop before this device says again what it has (see
         * [tellVersions]): one message after a burst of changes.
         */
        const val QUIET_MS = 2_000L

        /**
         * The largest frame of a device not authorized yet (see [Link.authorized]): its hello, a
         * membership of about a thousand members, an admission.
         */
        const val HANDSHAKE_FRAME_SIZE = 256 * 1024

        /** The incoming channels of devices not known yet at once (see [strangers]). */
        const val MAX_STRANGERS = 16
    }
}
