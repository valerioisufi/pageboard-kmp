package io.github.valerioisufi.drawview.sync.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.valerioisufi.drawview.model.DocumentAttachments
import io.github.valerioisufi.drawview.platform.DeviceProfile
import io.github.valerioisufi.drawview.store.DocumentStore
import io.github.valerioisufi.drawview.store.OpenDocuments
import io.github.valerioisufi.drawview.store.PreferenceStore
import io.github.valerioisufi.drawview.store.space.SpaceLibrary
import io.github.valerioisufi.drawview.store.space.SpaceStore
import io.github.valerioisufi.drawview.sync.InMemoryAddressBook
import io.github.valerioisufi.drawview.sync.InternetSync
import io.github.valerioisufi.drawview.sync.InvitationGrant
import io.github.valerioisufi.drawview.sync.JoinRequest
import io.github.valerioisufi.drawview.sync.JoinStatus
import io.github.valerioisufi.drawview.sync.Peer
import io.github.valerioisufi.drawview.sync.PreferenceAddressBook
import io.github.valerioisufi.drawview.sync.Presence
import io.github.valerioisufi.drawview.sync.SpaceRoot
import io.github.valerioisufi.drawview.sync.StoreSharedDocuments
import io.github.valerioisufi.drawview.sync.SyncEngine
import io.github.valerioisufi.drawview.sync.SyncNetwork
import io.github.valerioisufi.drawview.sync.lan.LanDiscovery
import io.github.valerioisufi.drawview.sync.lan.LanSync
import io.github.valerioisufi.drawview.sync.protocol.DeviceIdentity
import io.github.valerioisufi.drawview.sync.protocol.Invitation
import io.github.valerioisufi.drawview.sync.protocol.InvitationKind
import io.github.valerioisufi.drawview.sync.protocol.Member
import io.github.valerioisufi.drawview.sync.protocol.Membership
import io.github.valerioisufi.drawview.sync.protocol.Role
import io.github.valerioisufi.drawview.sync.protocol.Route
import io.github.valerioisufi.drawview.sync.protocol.SyncException
import io.github.valerioisufi.drawview.sync.ui.generated.resources.Res
import io.github.valerioisufi.drawview.sync.ui.generated.resources.sync_shared_folder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.getString
import io.github.valerioisufi.drawview.sync.protocol.ExperimentalSyncApi

/** How long the devices of the local network are looked for (see [Collaboration.nearbyHosts]). */
private const val NEARBY_WAIT_MS = 3_000L

/**
 * Working on the documents with the other devices (GRASP Controller of sharing and joining the
 * documents of an application): the documents shared are kept the same on every device as long as
 * the devices are on the same network, or wherever they are over the internet when the
 * application gives [internet] and the user turns it on ([internetSettings]); the documents
 * received go to the folder named by [sharedFolderName] in the library.
 *
 * The device sharing a document is its owner: it gives the invitations (with the role of the
 * devices joining with them, and whether it approves them first), changes the roles of the
 * members, removes them and ends the sharing. The devices show themselves with the name the user
 * chose ([DeviceProfile]) and prove who they are ([DeviceIdentity], kept by the system).
 *
 * The data the extensions keep beside the documents ([attachments], e.g. the lectures) travel
 * with them.
 *
 * When the store keeps spaces ([SpaceStore], the stores of the libraries do), whole folders and
 * the library are shared too ([shareFolder], see `docs/plans/shared-folders.md`): what they hold is kept
 * the same on every device of their members, the notes and folders added later too.
 *
 * The devices are looked for only once allowed to use the local network ([requestAccess]: the
 * permission of Android 17; asked when a document is shared or joined, or at the start when some
 * are shared already).
 */
@ExperimentalSyncApi
public class Collaboration(
    documents: OpenDocuments,
    private val discovery: LanDiscovery,
    private val sharedFolderName: suspend () -> String,
    private val scope: CoroutineScope,
    attachments: DocumentAttachments? = null,
    identity: DeviceIdentity = DeviceIdentity.ofThisDevice(),
    private val internet: InternetSync? = null
) {
    private val store: DocumentStore = documents.store
    private val shared = StoreSharedDocuments(store, documents)
    private val mutex = Mutex()
    private var lan: LanSync? = null
    private var internetNetwork: SyncNetwork? = null
    private val online = MutableStateFlow(false)
    private val settingsStore = InternetSettingsStore()
    private val internetSettingsState = MutableStateFlow(if (internet != null) settingsStore.load() else InternetSettings())

    /** Whether this device can work with the others over the internet (the application gives it, and it works here). */
    public val isInternetAvailable: Boolean get() = internet?.isAvailable == true

    /** Whether this device works with the others over the internet, and through which relay (see [setInternet]). */
    public val internetSettings: StateFlow<InternetSettings> = internetSettingsState.asStateFlow()

    /** Asks for the permission to use the local network (set by [CollaborationAccess]). */
    internal var requestAccess: suspend () -> Boolean = { true }

    /** The shared folders of this device, kept in step with the library, when the store keeps them. */
    private val library: SpaceLibrary? = (store as? SpaceStore)?.let { spaces ->
        SpaceLibrary(store, spaces, scope, sharedApart = { _, secret -> io.github.valerioisufi.drawview.sync.SharingState.decode(secret)?.space == null })
    }

    public val engine: SyncEngine = SyncEngine(
        documents, shared, scope, { DeviceProfile.name.value },
        attachments = attachments, identity = identity, receivedFolder = ::sharedFolder,
        // Where the other members can be reached, kept with the preferences of the store
        addressBook = (store as? PreferenceStore)?.let(::PreferenceAddressBook) ?: InMemoryAddressBook(),
        spaces = library
    )

    /** Whether folders and the library can be shared here (the store keeps spaces). */
    public val sharesFolders: Boolean get() = library != null

    /** Who works on each shared folder and on its documents, and with what role (as its owner decided). */
    public val spaceMemberships: StateFlow<Map<String, Membership>> get() = engine.spaceMemberships

    /** The names of the folders, by identifier. */
    internal val folderNames: Flow<Map<String, String>> = store.folders.map { list -> list.associate { it.id to it.name } }

    /** The devices connected, with the document each one works on. */
    public val peers: StateFlow<List<Peer>> get() = engine.peers

    /** Who works on each shared document, and with what role (as its owner decided). */
    public val memberships: StateFlow<Map<String, Membership>> get() = engine.memberships

    /** This device among the members (see [Member.device]). */
    public val device: String get() = engine.device

    /** The devices waiting for this device, the owner, to approve them. */
    public val joinRequests: StateFlow<List<JoinRequest>> get() = engine.joinRequests

    /** The titles of the documents, by identifier. */
    internal val documentTitles: Flow<Map<String, String>> = store.documents.map { list -> list.associate { it.id to it.name } }

    /** The documents being joined with an invitation, and where each one is. */
    public val joining: StateFlow<Map<String, JoinStatus>> get() = engine.joining

    /** Where the other devices are on the documents. */
    public val presences: StateFlow<List<Presence>> get() = engine.presences

    /** Whether a device with another version of the application was met (see [SyncEngine.otherVersionMet]). */
    public val otherVersionMet: StateFlow<Boolean> get() = engine.otherVersionMet

    /** Whether this device works with the devices of the network (allowed to and started). */
    internal val isOnline: StateFlow<Boolean> = online.asStateFlow()

    init {
        // The shared folders follow the library from the start, their documents their sharing
        library?.let { library ->
            scope.launch {
                library.start()
                engine.followSpaces()
            }
        }
        // Connected to some devices: going on in the background too
        scope.launch {
            engine.peers.map { peers -> peers.map { it.documentId }.distinct().size }.distinctUntilChanged().collect { documents ->
                BackgroundSync.update(active = documents > 0, documents = documents)
            }
        }
    }

    /**
     * Starts looking for the devices of the network, once allowed to (and over the internet, if
     * turned on); returns whether started.
     */
    public suspend fun start(): Boolean {
        startInternet()
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
    public suspend fun share(documentId: String, title: String, role: Role = Role.EDITOR, approval: Boolean = false): Invitation? {
        val invitation = engine.invite(documentId, title, role, approval)
        if (start()) lan?.announce()
        return invitation
    }

    /**
     * Shares the folder [folderId] (`null`: the library), titled [title], this device becoming its
     * owner if it is not shared yet: a new invitation, as [share]; `null` when the folders cannot be
     * shared here, the folder is in a shared folder or holds one, or this device is not its owner.
     */
    public suspend fun shareFolder(folderId: String?, title: String, role: Role = Role.EDITOR, approval: Boolean = false): Invitation? {
        val spaceId = spaceOf(folderId) ?: engine.shareSpace(folderId) ?: return null
        return share(spaceId, title, role, approval)
    }

    /** The shared folder whose root is [folderId] (`null`: the library), `null` when it is not shared. */
    public suspend fun spaceOf(folderId: String?): String? = library?.spaces()?.firstOrNull { it.rootFolderId == folderId }?.id

    /** The invitations of the document or shared folder [documentId] given by this device, its owner, as links. */
    public suspend fun invitationsOf(documentId: String, title: String): List<Pair<InvitationGrant, Invitation>> {
        val kind = library?.spaces()?.firstOrNull { it.id == documentId }
            ?.let { if (it.rootFolderId == null) InvitationKind.LIBRARY else InvitationKind.FOLDER } ?: InvitationKind.DOCUMENT
        return engine.invitationsOf(documentId).map { it to engine.invitationOf(documentId, title, it, kind = kind) }
    }

    /** Revokes an invitation: nobody joins with it anymore. */
    public suspend fun revokeInvitation(documentId: String, grant: InvitationGrant) {
        engine.revokeInvitation(documentId, grant.id)
        lan?.announce()
    }

    /** Approves (or refuses) a device joining. */
    public fun answer(request: JoinRequest, approve: Boolean): Unit = engine.answer(request, approve)

    /**
     * The hosts of the devices found on the local network within [waitMs] (e.g. a computer with
     * the models of the lectures, see LocalModels.nearbyHosts).
     */
    public suspend fun nearbyHosts(waitMs: Long = NEARBY_WAIT_MS): List<String> {
        val hosts = mutableSetOf<String>()
        withTimeoutOrNull(waitMs) {
            discovery.services.collect { services -> services.mapTo(hosts) { it.host } }
        }
        return hosts.toList()
    }

    /** Gives a member another role (this device is the owner). */
    public suspend fun setRole(documentId: String, member: String, role: Role): Unit = engine.setRole(documentId, member, role)

    /** Removes a member: the document gets a new key it does not have (this device is the owner). */
    public suspend fun removeMember(documentId: String, member: String): Unit = engine.removeMember(documentId, member)

    /**
     * Stops sharing the document [documentId]: for every device when this one is its owner (each
     * one keeps its copy), otherwise this device leaves it.
     */
    internal suspend fun stopSharing(documentId: String) {
        engine.unshare(documentId)
        lan?.announce()
    }

    /**
     * Joins the document or the shared folder of the invitation [link] (a folder going where
     * [root] says): the invitation, or `null` when it is not one.
     */
    public suspend fun join(link: String, root: SpaceRoot = SpaceRoot.NewFolder): Invitation? {
        val invitation = Invitation.parse(link) ?: return null
        if (invitation.kind != InvitationKind.DOCUMENT) engine.joinSpace(invitation, root)
        if (start()) lan?.join(invitation) else engine.join(invitation)
        // Over the internet too: the owner may be elsewhere
        mutex.withLock { internetNetwork }?.join(invitation)
        return invitation
    }

    /** Shows this device as [name] to the others (blank: the name of the device). */
    public suspend fun rename(name: String?) {
        DeviceProfile.rename(name)
        lan?.announce()
    }

    /**
     * Makes again at once the connections lost (e.g. when the application comes back to the
     * foreground: the system may have closed them meanwhile).
     */
    public suspend fun refresh() {
        val (lan, internet) = mutex.withLock { lan to internetNetwork }
        lan?.refresh(networkChanged = true)
        internet?.refresh(networkChanged = true)
    }

    /** Whether some documents are shared with other devices already. */
    internal suspend fun hasSharedDocuments(): Boolean = store.sharingSecrets().isNotEmpty() || library?.spaces()?.isNotEmpty() == true

    /** Stops working with the other devices (started again by [start]). */
    public suspend fun stop() {
        mutex.withLock {
            lan?.stop()
            lan = null
            internetNetwork?.stop()
            internetNetwork = null
            online.value = false
        }
    }

    /**
     * Works with the others over the internet or not, through the relay of [settings] (kept for
     * the next starts): the connections over the internet start again with them.
     */
    public suspend fun setInternet(settings: InternetSettings) {
        if (settings == internetSettingsState.value) return
        settingsStore.save(settings)
        internetSettingsState.value = settings
        mutex.withLock {
            internetNetwork?.stop()
            internetNetwork = null
        }
        // Over the internet once some documents are shared (or when the user shares or joins one)
        if (online.value || store.sharingSecrets().isNotEmpty()) startInternet()
    }

    /** Starts working over the internet, when turned on and possible. */
    private suspend fun startInternet() {
        val settings = internetSettingsState.value
        val internet = internet?.takeIf { settings.enabled && it.isAvailable } ?: return
        val network = mutex.withLock {
            if (internetNetwork != null) return
            internet.network(engine, scope, settings.relay, settings.token).also { internetNetwork = it }
        }
        // Started outside of the lock: it waits for its relay
        try {
            network.start()
        } catch (_: SyncException) {
            mutex.withLock { if (internetNetwork === network) internetNetwork = null }
        }
    }

    /** What the connection with the device [device] about [documentId] goes through, `null` when not connected. */
    public suspend fun routeOf(documentId: String, device: String): Route? = engine.routeOf(documentId, device)

    /** The folder of the documents received, created the first time. */
    private suspend fun sharedFolder(): String? {
        val name = sharedFolderName()
        suspend fun find() = store.folders.first().firstOrNull { it.parentId == null && it.name == name }?.id
        find()?.let { return it }
        store.createFolder(name, null)
        return find()
    }
}

/**
 * The [Collaboration] of the application, one per process (it listens on the network): the
 * discovery of the devices of this platform, the documents received in a folder named in the
 * language of the application, over the internet too with [internet] (e.g. `IrohInternet` of
 * `pageboard-sync-iroh`) once the user turns it on. It works in [scope] until [Collaboration.stop];
 * each window of the application binds it with [CollaborationAccess].
 */
public fun createCollaboration(
    documents: OpenDocuments,
    attachments: DocumentAttachments? = null,
    internet: InternetSync? = null,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
): Collaboration = Collaboration(
    documents, createLanDiscovery(), { getString(Res.string.sync_shared_folder) }, scope, attachments, internet = internet
)

/**
 * Binds [collaboration] to this window: the permission of the local network asked from it, the
 * collaboration started when some documents are shared already, and the connections closed
 * meanwhile made again at once when the window comes back to the foreground. The invitations
 * opened from outside are joined through [CollaborationDialogs].
 */
@Composable
public fun CollaborationAccess(collaboration: Collaboration) {
    val requestAccess = rememberLocalNetworkAccess()
    SideEffect { collaboration.requestAccess = requestAccess }
    LaunchedEffect(collaboration) {
        if (collaboration.hasSharedDocuments()) collaboration.start()
    }
    val scope = rememberCoroutineScope()
    LifecycleResumeEffect(collaboration) {
        scope.launch { collaboration.refresh() }
        onPauseOrDispose {}
    }
}

/**
 * The invitations opened from outside of the application (a `drawview://join` link opened on the
 * device), waiting for [CollaborationDialogs] to join them.
 */
public object InvitationLinks {
    private val pending = MutableStateFlow<String?>(null)

    internal val link: StateFlow<String?> = pending.asStateFlow()

    /** Opens the invitation [link] (from the platform: an intent, a URL opened...). */
    public fun open(link: String) {
        if (Invitation.parse(link) != null) pending.value = link
    }

    internal fun consume() {
        pending.value = null
    }
}
