package io.github.valerioisufi.drawview.sync.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.valerioisufi.drawview.data.DocumentStore
import io.github.valerioisufi.drawview.data.DrawViewStore
import io.github.valerioisufi.drawview.data.OpenDocuments
import io.github.valerioisufi.drawview.sync.Invitation
import io.github.valerioisufi.drawview.sync.Peer
import io.github.valerioisufi.drawview.sync.Presence
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.compose.resources.stringResource

/**
 * Working on the documents with the other devices of the local network (GRASP Controller of
 * sharing and joining the documents of an application): the documents shared are kept the same
 * on every device as long as the devices are on the same network; the documents received go to
 * the folder [sharedFolderName] of the library.
 *
 * The devices are looked for only once allowed to use the local network ([requestAccess]: the
 * permission of Android 17; asked when a document is shared or joined, or at the start when some
 * are shared already).
 */
class Collaboration(
    private val store: DocumentStore,
    documents: OpenDocuments,
    private val discovery: LanDiscovery,
    private val deviceName: String,
    private val sharedFolderName: String,
    private val scope: CoroutineScope
) {
    private val shared = StoreSharedDocuments(store)
    private val mutex = Mutex()
    private var lan: LanSync? = null
    private val online = MutableStateFlow(false)

    /** Asks for the permission to use the local network (set by [rememberCollaboration]). */
    internal var requestAccess: suspend () -> Boolean = { true }

    val engine = SyncEngine(documents, shared, scope, deviceName, receivedFolder = ::sharedFolder)

    /** The devices connected, with the document each one works on. */
    val peers: StateFlow<List<Peer>> get() = engine.peers

    /** Where the other devices are on the documents. */
    val presences: StateFlow<List<Presence>> get() = engine.presences

    /** Whether a device with another version of the application was met (see [SyncEngine.otherVersionMet]). */
    val otherVersionMet: StateFlow<Boolean> get() = engine.otherVersionMet

    /** Whether this device works with the devices of the network (allowed to and started). */
    val isOnline: StateFlow<Boolean> = online.asStateFlow()

    /** Starts looking for the devices of the network, once allowed to; returns whether started. */
    suspend fun start(): Boolean {
        if (online.value) return true
        if (!requestAccess()) return false
        mutex.withLock {
            if (lan == null) {
                lan = LanSync(engine, shared, discovery, scope, store.replicaId(), deviceName).also { it.start() }
                online.value = true
            }
        }
        return true
    }

    /** Shares the document [documentId] (titled [title]): its invitation. */
    suspend fun share(documentId: String, title: String): Invitation {
        val invitation = engine.share(documentId, title)
        if (start()) lan?.announce()
        return invitation
    }

    /** The invitation of the document [documentId] if it is shared, otherwise `null`. */
    suspend fun invitationOf(documentId: String, title: String): Invitation? =
        shared.keyOf(documentId)?.let { Invitation(documentId, title, it) }

    /** Stops sharing the document [documentId] (from this device). */
    suspend fun stopSharing(documentId: String) {
        engine.unshare(documentId)
        lan?.announce()
    }

    /** Joins the document of the invitation [link]: the invitation, or `null` when it is not one. */
    suspend fun join(link: String): Invitation? {
        val invitation = Invitation.parse(link) ?: return null
        shared.add(invitation.documentId, invitation.key)
        if (start()) lan?.join(invitation)
        return invitation
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
    documents: OpenDocuments = DrawViewStore.documents
): Collaboration {
    val folderName = stringResource(Res.string.sync_shared_folder)
    val holder = viewModel { CollaborationHolder(store, documents, folderName) }
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
private class CollaborationHolder(store: DocumentStore, documents: OpenDocuments, folderName: String) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val collaboration = Collaboration(store, documents, createLanDiscovery(), localDeviceName(), folderName, scope)

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
