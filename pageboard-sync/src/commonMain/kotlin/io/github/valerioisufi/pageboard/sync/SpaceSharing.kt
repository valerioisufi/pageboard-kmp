package io.github.valerioisufi.pageboard.sync

import io.github.valerioisufi.pageboard.sync.protocol.DeviceIdentity
import io.github.valerioisufi.pageboard.sync.protocol.MembershipKind
import io.github.valerioisufi.pageboard.sync.protocol.OperationProof
import io.github.valerioisufi.pageboard.sync.protocol.Role
import io.github.valerioisufi.pageboard.store.space.CatalogItemKind
import io.github.valerioisufi.pageboard.store.space.CatalogOperation
import io.github.valerioisufi.pageboard.store.space.SpaceLibrary
import io.github.valerioisufi.pageboard.store.space.StoredSpace

/**
 * The sharing of the shared folders of this device (GRASP Information Expert of who may do what on
 * them, see `docs/plans/shared-folders.md`):
 *
 * * each space has a key with its epochs, an owner, a membership signed by the owner
 *   ([MembershipKind.SPACE]) and invitations, kept with the space ([StoredSpace.sharing]) and
 *   decided as for a document ([spaces]: a [DocumentSharing] of spaces);
 * * each document of a space gets its sharing from the space ([follow]): its keys derived from the
 *   ones of the space ([io.github.valerioisufi.pageboard.sync.protocol.DocumentKey.forDocument]),
 *   the owner and the membership of the space, so that the engine works on it as on a document
 *   shared on its own, and a member admitted, made reader or removed is so for every document;
 * * a change of the catalog is taken signed by the device of its replica ([admitsChange]), a
 *   document from the device that brought it or from the owner ([mayGive]).
 */
internal class SpaceSharing(
    private val library: SpaceLibrary,
    identity: DeviceIdentity,
    private val documents: DocumentSharing
) {
    /** The sharing of the spaces themselves. */
    val spaces: DocumentSharing = DocumentSharing(SpaceStates(library), identity, MembershipKind.SPACE)

    private val device: String get() = spaces.device

    /** Shares the space [spaceId] (this device becomes its owner), then its documents. */
    suspend fun share(spaceId: String, replica: String, name: String): SharingState {
        val state = spaces.share(spaceId, replica, name)
        follow(spaceId)
        return state
    }

    /**
     * The sharing of the document [documentId] of [spaceId], as the space gives it: `null` when this
     * device has no key of the space yet.
     */
    suspend fun documentState(spaceId: String, documentId: String): SharingState? {
        val state = spaces.stateOf(spaceId) ?: return null
        if (state.keys.isEmpty()) return null
        val keys = state.keys.mapNotNull { epoch -> epoch.documentKey?.let { EpochKey(epoch.epoch, it.forDocument(documentId).encode()) } }
        return SharingState(keys = keys, owner = state.owner, membership = state.membership, closed = state.closed, space = spaceId)
    }

    /**
     * Gives every document of [spaceId] its sharing from the space (after a change of its keys or
     * of its membership, or of what it holds); the documents that left the space are not shared
     * through it anymore (each device keeps its copy).
     */
    suspend fun follow(spaceId: String) {
        val inSpace = library.view(spaceId).filter { it.kind == CatalogItemKind.DOCUMENT }.map { it.id }.toSet()
        for (documentId in inSpace) {
            val state = documentState(spaceId, documentId) ?: break
            documents.follow(documentId, state)
        }
        for ((documentId, state) in documents.all()) {
            if (state.space == spaceId && documentId !in inSpace) documents.forget(documentId)
        }
    }

    /** This device leaves [spaceId] (left, removed, the sharing ended): neither the space nor its documents are shared anymore. */
    suspend fun forget(spaceId: String) {
        for ((documentId, state) in documents.all()) {
            if (state.space == spaceId) documents.forget(documentId)
        }
        spaces.forget(spaceId)
    }

    /**
     * Whether [operation] of the catalog of [spaceId], with its [proof], may be taken: signed by the
     * device of its replica (a member's, or a removed one's) over its content, and that device
     * allowed to write it (an editor or the owner; a reader's or a removed member's changes up to
     * where it could write).
     */
    suspend fun admitsChange(spaceId: String, operation: CatalogOperation, proof: OperationProof): Boolean {
        val membership = spaces.membershipOf(spaceId) ?: return false
        val id = operation.id
        val author = membership.deviceOf(id.replica) ?: return false
        if (!proof.isCatalogBy(author, spaceId, id.replica, id.sequence)) return false
        return membership.mayWrite(id.replica, id.sequence)
    }

    /**
     * Whether [giver] may give the document [documentId] of [spaceId] to a device without it: the
     * owner of the space, or the member whose replica brought it into the space (see
     * [io.github.valerioisufi.pageboard.store.space.CatalogItem.broughtBy]).
     */
    suspend fun mayGive(spaceId: String, documentId: String, giver: String): Boolean {
        val state = spaces.stateOf(spaceId) ?: return false
        if (giver == state.owner) return true
        val membership = state.members ?: return false
        val broughtBy = library.view(spaceId).firstOrNull { it.id == documentId && it.kind == CatalogItemKind.DOCUMENT }?.broughtBy ?: return false
        return membership.deviceOf(broughtBy) == giver
    }

    /** The role of this device in [spaceId], `null` when it is not a member. */
    suspend fun roleOf(spaceId: String): Role? = spaces.membershipOf(spaceId)?.memberOf(device)?.role
}

/**
 * The sharing of the spaces kept with the spaces of a [SpaceLibrary] (see [StoredSpace.sharing]): a
 * space not in the library yet (joining) is kept in memory until it is joined.
 */
internal class SpaceStates(private val library: SpaceLibrary) : SharedDocuments {
    private val waiting = mutableMapOf<String, SharingState>()

    override suspend fun stateOf(documentId: String): SharingState? =
        waiting[documentId] ?: library.spaces().firstOrNull { it.id == documentId }?.sharing?.let(SharingState::decode)

    override suspend fun save(documentId: String, state: SharingState, readOnly: Boolean) {
        val space = library.spaces().firstOrNull { it.id == documentId }
        if (space == null) {
            waiting[documentId] = state
            return
        }
        waiting.remove(documentId)
        library.update(space.copy(sharing = state.encode(), readOnly = readOnly))
    }

    override suspend fun remove(documentId: String) {
        waiting.remove(documentId)
        library.leave(documentId)
    }

    override suspend fun all(): Map<String, SharingState> =
        library.spaces().mapNotNull { space -> SharingState.decode(space.sharing)?.let { space.id to it } }.toMap() + waiting
}
