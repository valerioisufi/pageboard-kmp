package io.github.valerioisufi.drawview.sync.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.valerioisufi.drawview.core.attachment.DocumentAttachments
import io.github.valerioisufi.drawview.platform.DeviceProfile
import io.github.valerioisufi.drawview.data.DocumentStore
import io.github.valerioisufi.drawview.data.DrawViewStore
import io.github.valerioisufi.drawview.data.OpenDocuments
import io.github.valerioisufi.drawview.sync.DeviceIdentity
import io.github.valerioisufi.drawview.sync.Invitation
import io.github.valerioisufi.drawview.sync.InvitationGrant
import io.github.valerioisufi.drawview.sync.JoinRequest
import io.github.valerioisufi.drawview.sync.JoinStatus
import io.github.valerioisufi.drawview.sync.Member
import io.github.valerioisufi.drawview.sync.Membership
import io.github.valerioisufi.drawview.sync.Peer
import io.github.valerioisufi.drawview.sync.Presence
import io.github.valerioisufi.drawview.sync.Role
import io.github.valerioisufi.drawview.sync.StoreSharedDocuments
import io.github.valerioisufi.drawview.sync.SyncEngine
import io.github.valerioisufi.drawview.sync.lan.LanDiscovery
import io.github.valerioisufi.drawview.sync.lan.LanSync
import io.github.valerioisufi.drawview.sync.ui.generated.resources.Res
import io.github.valerioisufi.drawview.sync.ui.generated.resources.sync_shared_folder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.stringResource

/** How long the devices of the local network are looked for (see [Collaboration.nearbyHosts]). */
private const val NEARBY_WAIT_MS = 3_000L

/**
 * Working on the documents with the other devices of the local network (GRASP Controller of
 * sharing and joining the documents of an application): the documents shared are kept the same
 * on every device as long as the devices are on the same network; the documents received go to
 * the folder [sharedFolderName] of the library.
 *
 * The device sharing a document is its owner: it gives the invitations (with the role of the
 * devices joining with them, and whether it approves them first), changes the roles of the
 * members, removes them and ends the sharing. The devices show themselves with the name the user
 * chose ([DeviceProfile]) and prove who they are ([DeviceIdentity], kept by the system).
 *
 * The data the extensions keep beside the documents ([attachments], e.g. the lectures) travel
 * with them.
 *
 * The devices are looked for only once allowed to use the local network ([requestAccess]: the
 * permission of Android 17; asked when a document is shared or joined, or at the start when some
 * are shared already).
 */
class Collaboration(
    private val store: DocumentStore,
    documents: OpenDocuments,
    private val discovery: LanDiscovery,
    private val sharedFolderName: String,
    private val scope: CoroutineScope,
    attachments: DocumentAttachments? = null,
    identity: DeviceIdentity = DeviceIdentity.ofThisDevice()
) {
    private val shared = StoreSharedDocuments(store, documents)
    private val mutex = Mutex()
    private var lan: LanSync? = null
    private val online = MutableStateFlow(false)

    /** Asks for the permission to use the local network (set by [rememberCollaboration]). */
    internal var requestAccess: suspend () -> Boolean = { true }

    val engine = SyncEngine(
        documents, shared, scope, { DeviceProfile.name.value },
        attachments = attachments, identity = identity, receivedFolder = ::sharedFolder
    )

    /** The devices connected, with the document each one works on. */
    val peers: StateFlow<List<Peer>> get() = engine.peers

    /** Who works on each shared document, and with what role (as its owner decided). */
    val memberships: StateFlow<Map<String, Membership>> get() = engine.memberships

    /** This device among the members (see [Member.device]). */
    val device: String get() = engine.device

    /** The devices waiting for this device, the owner, to approve them. */
    val joinRequests: StateFlow<List<JoinRequest>> get() = engine.joinRequests

    /** The titles of the documents, by identifier. */
    val documentTitles: Flow<Map<String, String>> = store.documents.map { list -> list.associate { it.id to it.name } }

    /** The documents being joined with an invitation, and where each one is. */
    val joining: StateFlow<Map<String, JoinStatus>> get() = engine.joining

    /** Where the other devices are on the documents. */
    val presences: StateFlow<List<Presence>> get() = engine.presences

    /** Whether a device with another version of the application was met (see [SyncEngine.otherVersionMet]). */
    val otherVersionMet: StateFlow<Boolean> get() = engine.otherVersionMet

    /** Whether this device works with the devices of the network (allowed to and started). */
    val isOnline: StateFlow<Boolean> = online.asStateFlow()

    init {
        // Connected to some devices: going on in the background too
        scope.launch {
            engine.peers.map { peers -> peers.map { it.documentId }.distinct().size }.distinctUntilChanged().collect { documents ->
                BackgroundSync.update(active = documents > 0, documents = documents)
            }
        }
    }

    /** Starts looking for the devices of the network, once allowed to; returns whether started. */
    suspend fun start(): Boolean {
        if (online.value) return true
        if (!requestAccess()) return false
        mutex.withLock {
            if (lan == null) {
                lan = LanSync(engine, discovery, scope, store.replicaId(), { DeviceProfile.name.value }).also { it.start() }
                online.value = true
            }
        }
        return true
    }

    /**
     * Shares the document [documentId] (titled [title]), this device becoming its owner if it is
     * not shared yet: a new invitation for the devices joining as [role], approved first when
     * [approval]; `null` when this device is not the owner.
     */
    suspend fun share(documentId: String, title: String, role: Role = Role.EDITOR, approval: Boolean = false): Invitation? {
        val invitation = engine.invite(documentId, title, role, approval)
        if (start()) lan?.announce()
        return invitation
    }

    /** The invitations of the document [documentId] given by this device, its owner, as links. */
    suspend fun invitationsOf(documentId: String, title: String): List<Pair<InvitationGrant, Invitation>> =
        engine.invitationsOf(documentId).map { it to engine.invitationOf(documentId, title, it) }

    /** Revokes an invitation: nobody joins with it anymore. */
    suspend fun revokeInvitation(documentId: String, grant: InvitationGrant) {
        engine.revokeInvitation(documentId, grant.id)
        lan?.announce()
    }

    /** Approves (or refuses) a device joining. */
    fun answer(request: JoinRequest, approve: Boolean) = engine.answer(request, approve)

    /**
     * The hosts of the devices found on the local network within [waitMs] (e.g. a computer with
     * the models of the lectures, see LocalModels.nearbyHosts).
     */
    suspend fun nearbyHosts(waitMs: Long = NEARBY_WAIT_MS): List<String> {
        val hosts = mutableSetOf<String>()
        withTimeoutOrNull(waitMs) {
            discovery.services.collect { services -> services.mapTo(hosts) { it.host } }
        }
        return hosts.toList()
    }

    /** Gives a member another role (this device is the owner). */
    suspend fun setRole(documentId: String, member: String, role: Role) = engine.setRole(documentId, member, role)

    /** Removes a member: the document gets a new key it does not have (this device is the owner). */
    suspend fun removeMember(documentId: String, member: String) = engine.removeMember(documentId, member)

    /**
     * Stops sharing the document [documentId]: for every device when this one is its owner (each
     * one keeps its copy), otherwise this device leaves it.
     */
    suspend fun stopSharing(documentId: String) {
        engine.unshare(documentId)
        lan?.announce()
    }

    /** Joins the document of the invitation [link]: the invitation, or `null` when it is not one. */
    suspend fun join(link: String): Invitation? {
        val invitation = Invitation.parse(link) ?: return null
        if (start()) lan?.join(invitation) else engine.join(invitation)
        return invitation
    }

    /** Shows this device as [name] to the others (blank: the name of the device). */
    suspend fun rename(name: String?) {
        DeviceProfile.rename(name)
        lan?.announce()
    }

    /**
     * Makes again at once the connections lost (e.g. when the application comes back to the
     * foreground: the system may have closed them meanwhile).
     */
    suspend fun refresh() {
        mutex.withLock { lan }?.refresh(announceAgain = true)
    }

    suspend fun stop() {
        mutex.withLock {
            lan?.stop()
            lan = null
            online.value = false
        }
    }

    /** The folder of the documents received, created the first time. */
    private suspend fun sharedFolder(): String? {
        suspend fun find() = store.folders.first().firstOrNull { it.parentId == null && it.name == sharedFolderName }?.id
        find()?.let { return it }
        store.createFolder(sharedFolderName, null)
        return find()
    }
}

/**
 * The [Collaboration] of the application (one per process: it listens on the network), started at
 * once when some documents are shared already. Joins the invitations opened from outside (see
 * [InvitationLinks]) through [CollaborationDialogs].
 */
@Composable
fun rememberCollaboration(
    store: DocumentStore = DrawViewStore.default,
    documents: OpenDocuments = DrawViewStore.documents,
    attachments: DocumentAttachments? = null
): Collaboration {
    val folderName = stringResource(Res.string.sync_shared_folder)
    val holder = viewModel { CollaborationHolder(store, documents, folderName, attachments) }
    val requestAccess = rememberLocalNetworkAccess()
    val collaboration = holder.collaboration
    collaboration.requestAccess = requestAccess
    LaunchedEffect(collaboration) {
        if (store.sharingSecrets().isNotEmpty()) collaboration.start()
    }
    // Back to the foreground: the connections closed meanwhile are made again at once
    val scope = rememberCoroutineScope()
    LifecycleResumeEffect(collaboration) {
        scope.launch { collaboration.refresh() }
        onPauseOrDispose {}
    }
    return collaboration
}

/** Keeps the collaboration across the configuration changes of Android. */
private class CollaborationHolder(
    store: DocumentStore,
    documents: OpenDocuments,
    folderName: String,
    attachments: DocumentAttachments?
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val collaboration = Collaboration(store, documents, createLanDiscovery(), folderName, scope, attachments)

    override fun onCleared() {
        scope.launch { collaboration.stop() }.invokeOnCompletion { scope.cancel() }
    }
}

/**
 * The invitations opened from outside of the application (a `drawview://join` link opened on the
 * device), waiting for [CollaborationDialogs] to join them.
 */
object InvitationLinks {
    private val pending = MutableStateFlow<String?>(null)

    internal val link: StateFlow<String?> = pending.asStateFlow()

    /** Opens the invitation [link] (from the platform: an intent, a URL opened...). */
    fun open(link: String) {
        if (Invitation.parse(link) != null) pending.value = link
    }

    internal fun consume() {
        pending.value = null
    }
}
