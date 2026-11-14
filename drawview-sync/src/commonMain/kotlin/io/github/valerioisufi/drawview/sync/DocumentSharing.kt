package io.github.valerioisufi.drawview.sync

import io.github.valerioisufi.drawview.platform.currentTimeMillis
import io.github.valerioisufi.drawview.sync.protocol.DeviceIdentity
import io.github.valerioisufi.drawview.sync.protocol.DocumentKey
import io.github.valerioisufi.drawview.sync.protocol.Invitation
import io.github.valerioisufi.drawview.sync.protocol.Member
import io.github.valerioisufi.drawview.sync.protocol.Membership
import io.github.valerioisufi.drawview.sync.protocol.MembershipKind
import io.github.valerioisufi.drawview.sync.protocol.Role
import io.github.valerioisufi.drawview.sync.protocol.SignedMembership
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What a connection is about, found by the tag of its first frame (see [DocumentSharing.match]). */
internal sealed interface TagMatch {
    val documentId: String
    val key: DocumentKey

    /** A key of the document (of any epoch): between members. */
    data class Member(override val documentId: String, override val key: DocumentKey) : TagMatch

    /** An invitation of this device, the owner: a device joining. */
    data class Grant(override val documentId: String, override val key: DocumentKey, val grant: InvitationGrant) : TagMatch

    /** The invitation this device joins with: to the owner. */
    data class Joining(override val documentId: String, override val key: DocumentKey, val invitation: Invitation) : TagMatch
}

/**
 * The sharing of the documents of this device (GRASP Information Expert of who may do what on
 * them): their keys, owners, memberships and invitations, kept by [shared]. The decisions of the
 * owner (admitting a device, its role, removing it, ending the sharing) are written and signed
 * here with [identity]; the memberships received are accepted only when signed by the owner.
 */
internal class DocumentSharing(
    private val shared: SharedDocuments,
    val identity: DeviceIdentity,
    /** What the memberships kept here are of: documents, or shared folders (see [SpaceSharing]). */
    private val kind: MembershipKind = MembershipKind.DOCUMENT
) {
    private val mutex = Mutex()
    private var cache: MutableMap<String, SharingState>? = null
    private val joiningInvitations = mutableMapOf<String, Invitation>()

    private val membershipsState = MutableStateFlow<Map<String, Membership>>(emptyMap())

    /** The memberships of the shared documents, by document. */
    val memberships: StateFlow<Map<String, Membership>> = membershipsState.asStateFlow()

    private val changedFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /** Documents whose keys or invitations changed (what to announce and connect with). */
    val changed: SharedFlow<String> = changedFlow

    val device: String get() = identity.publicKey

    private suspend fun states(): MutableMap<String, SharingState> =
        cache ?: shared.all().toMutableMap().also {
            cache = it
            publish(it)
        }

    suspend fun stateOf(documentId: String): SharingState? = mutex.withLock { states()[documentId] }

    suspend fun all(): Map<String, SharingState> = mutex.withLock { states().toMap() }

    /** The membership of [documentId] (signed by its owner). */
    suspend fun membershipOf(documentId: String): Membership? = stateOf(documentId)?.members

    /** Changes the sharing of [documentId] by [transform] (`null`: not shared anymore); the new one. */
    private suspend fun update(documentId: String, transform: (SharingState?) -> SharingState?): SharingState? = mutex.withLock {
        val states = states()
        val updated = transform(states[documentId])
        if (updated == null) {
            states.remove(documentId)
            shared.remove(documentId)
        } else {
            states[documentId] = updated
            shared.save(documentId, updated, readOnly = updated.roleOf(device) == Role.READER)
        }
        publish(states)
        updated
    }.also { changedFlow.tryEmit(documentId) }

    private fun publish(states: Map<String, SharingState>) {
        membershipsState.value = states.mapNotNull { (id, state) -> state.members?.let { id to it } }.toMap()
    }

    // ==========================================================
    // The owner
    // ==========================================================

    /** Shares [documentId] (this device becomes its owner), unless it is shared already. */
    suspend fun share(documentId: String, replica: String, name: String): SharingState = update(documentId) { state ->
        if (state?.members != null) return@update state
        val key = DocumentKey.generate()
        val membership = Membership(documentId, owner = device, members = listOf(Member(device, replica, name, Role.OWNER)), keyHash = key.hash, kind = kind)
        SharingState(
            keys = listOf(EpochKey(1, key.encode())),
            owner = device,
            membership = membership.signedBy(identity)
        )
    }!!

    /** A new invitation to [documentId] (this device is its owner): devices joining as [role], approved first when [approval]. */
    suspend fun invite(documentId: String, role: Role, approval: Boolean): InvitationGrant? {
        val grant = InvitationGrant(newId(), DocumentKey.generate().encode(), role, approval, currentTimeMillis())
        val state = update(documentId) { state -> if (state?.owner != device) state else state.copy(invitations = state.invitations + grant) }
        return grant.takeIf { state?.invitations?.contains(grant) == true }
    }

    /** Revokes the invitation [grantId]: nobody joins with it anymore. */
    suspend fun revoke(documentId: String, grantId: String) {
        update(documentId) { state -> state?.copy(invitations = state.invitations.filter { it.id != grantId }) }
    }

    /** Admits the device [newDevice] (of [replica], named [name]) as [role]: the membership signed again. */
    suspend fun admit(documentId: String, newDevice: String, replica: String, name: String, role: Role): SharingState? = ownerChange(documentId) { membership ->
        membership.copy(
            members = membership.members.filter { it.device != newDevice } + Member(newDevice, replica, name, role.takeIf { it != Role.OWNER } ?: Role.EDITOR),
            removed = membership.removed.filter { it.device != newDevice }
        )
    }

    /**
     * Gives the device [target] the role [role]: made reader, its operations after [lastSequence]
     * (the last one this device has) are refused.
     */
    suspend fun setRole(documentId: String, target: String, role: Role, lastSequence: Long): SharingState? = ownerChange(documentId) { membership ->
        if (role == Role.OWNER || target == device) return@ownerChange null
        membership.copy(
            members = membership.members.map {
                if (it.device != target) it else it.copy(role = role, writesUpTo = if (role == Role.READER) lastSequence else null)
            }
        )
    }

    /**
     * Removes the device [target] (its operations after [lastSequence] refused) and makes a new key
     * ([Membership.epoch]): it cannot follow the document anymore.
     */
    suspend fun remove(documentId: String, target: String, lastSequence: Long): SharingState? {
        val newKey = DocumentKey.generate().encode()
        return ownerChange(documentId, newKey) { membership ->
            val member = membership.memberOf(target) ?: return@ownerChange null
            if (target == device) return@ownerChange null
            membership.copy(
                epoch = membership.epoch + 1,
                members = membership.members - member,
                removed = membership.removed + member.copy(writesUpTo = lastSequence)
            )
        }
    }

    /** Ends the sharing of [documentId] for every device (each one keeps its copy). */
    suspend fun end(documentId: String): SharingState? = ownerChange(documentId) { it.copy(ended = true) }

    /** A change of the membership by its owner, signed: [newKey] for the new epoch it makes. */
    private suspend fun ownerChange(documentId: String, newKey: String? = null, transform: (Membership) -> Membership?): SharingState? {
        var changed = false
        val state = update(documentId) { state ->
            // The membership of a document of a shared folder is the folder's: changed there only
            val membership = state?.members?.takeIf { state.owner == device && state.space == null } ?: return@update state
            val changedMembership = transform(membership)?.copy(version = membership.version + 1) ?: return@update state
            val keys = if (changedMembership.epoch > state.epoch && newKey != null) state.keys + EpochKey(changedMembership.epoch, newKey) else state.keys
            // The key of its epoch named (a membership of before gets it at its first change)
            val key = keys.lastOrNull { it.epoch == changedMembership.epoch }?.key?.let(DocumentKey::decode)
            val next = changedMembership.copy(keyHash = key?.hash ?: changedMembership.keyHash)
            changed = true
            state.copy(membership = next.signedBy(identity), keys = keys)
        }
        return state.takeIf { changed }
    }

    // ==========================================================
    // Received
    // ==========================================================

    /** Keeps [signed] if it is more recent and signed by the owner: the new membership, otherwise `null`. */
    suspend fun adopt(documentId: String, signed: SignedMembership): Membership? {
        var adopted: Membership? = null
        update(documentId) { state ->
            val membership = signed.open(state?.owner ?: return@update state) ?: return@update state
            if (!isOf(membership, documentId, state) || membership.version <= (state.members?.version ?: 0)) return@update state
            adopted = membership
            state.copy(membership = signed)
        }
        return adopted
    }

    /**
     * Keeps the key of [epoch] (given by a member: up to the epoch of the membership); when the
     * membership names its key ([Membership.keyHash]), that one only.
     */
    suspend fun adoptKey(documentId: String, epoch: Int, key: String): Boolean {
        var adopted = false
        update(documentId) { state ->
            val decoded = DocumentKey.decode(key)
            // The keys of a document of a shared folder come from the folder
            if (state == null || state.space != null || decoded == null || state.keys.any { it.epoch == epoch }) return@update state
            val members = state.members
            if (epoch > (members?.epoch ?: 0)) return@update state
            if (members != null && members.keyHash.isNotEmpty() && (epoch != members.epoch || decoded.hash != members.keyHash)) return@update state
            adopted = true
            state.copy(keys = state.keys + EpochKey(epoch, key))
        }
        return adopted
    }

    /** Joins [invitation]: the owner admits this device when it reaches it. */
    suspend fun startJoining(invitation: Invitation) {
        mutex.withLock { joiningInvitations[invitation.documentId] = invitation }
        update(invitation.documentId) { state -> if (state?.members != null) state else SharingState(joining = invitation.toLink()) }
    }

    /** The owner [owner] admitted this device to [documentId]: its key and the membership. */
    suspend fun admitted(documentId: String, owner: String, epoch: Int, key: String, signed: SignedMembership): Membership? {
        val membership = signed.open(owner)?.takeIf { it.documentId == documentId && it.kind == kind && it.memberOf(device) != null } ?: return null
        if (DocumentKey.decode(key) == null) return null
        mutex.withLock { joiningInvitations.remove(documentId) }
        update(documentId) { SharingState(keys = listOf(EpochKey(epoch, key)), owner = owner, membership = signed) }
        return membership
    }

    /**
     * Keeps [state] for [documentId], a document of the shared folder [SharingState.space] (its keys,
     * owner and membership the folder's), with what this device knows of its members' operations
     * ([SharingState.versions]) kept; `null` when the document is shared on its own (its sharing is
     * not the folder's to change).
     */
    suspend fun follow(documentId: String, state: SharingState): SharingState? {
        var followed = false
        update(documentId) { previous ->
            if (previous != null && previous.space == null && previous.members != null) return@update previous
            followed = true
            state.copy(versions = previous?.versions.orEmpty())
        }
        return if (followed) stateOf(documentId) else null
    }

    /** Whether [membership] is the one of [documentId]: its own, or the one of its shared folder. */
    private fun isOf(membership: Membership, documentId: String, state: SharingState): Boolean = when (val space = state.space) {
        null -> membership.documentId == documentId && membership.kind == kind
        else -> membership.documentId == space && membership.kind == MembershipKind.SPACE
    }

    /** Saves the sharing of [documentId] again (e.g. the document received: kept with it). */
    suspend fun saveAgain(documentId: String) {
        update(documentId) { it }
    }

    /** This device does not share [documentId] anymore (it left, was removed, the sharing ended). */
    suspend fun forget(documentId: String) {
        mutex.withLock { joiningInvitations.remove(documentId) }
        update(documentId) { null }
    }

    /** Remembers the operations the members have ([versions], by device): the log is compacted with them. */
    suspend fun recordVersions(documentId: String, versions: Map<String, Map<String, Long>>) {
        update(documentId) { state ->
            val members = state?.members ?: return@update state
            val known = state.versions.toMutableMap()
            var changed = false
            for ((member, vector) in versions) {
                if (members.memberOf(member) == null || member == device) continue
                val merged = merge(known[member].orEmpty(), vector)
                if (merged != known[member]) {
                    known[member] = merged
                    changed = true
                }
            }
            // The devices no longer members are forgotten
            known.keys.retainAll { members.memberOf(it) != null }
            if (changed || known.size != state.versions.size) state.copy(versions = known) else state
        }
    }

    // ==========================================================
    // Connections
    // ==========================================================

    /** What the connection of tag [tag] is about, `null` when it is about nothing this device has. */
    suspend fun match(tag: String): TagMatch? = mutex.withLock {
        for ((documentId, state) in states()) {
            if (state.closed) continue
            state.keys.mapNotNull { it.documentKey }.firstOrNull { it.tag == tag }?.let { return TagMatch.Member(documentId, it) }
            for (grant in state.invitations) {
                val key = DocumentKey.decode(grant.key) ?: continue
                if (key.tag == tag) return TagMatch.Grant(documentId, key, grant)
            }
            val joining = joiningInvitations[documentId] ?: state.joining?.let(Invitation::parse)
            joining?.takeIf { state.members == null && it.key.tag == tag }?.let { return TagMatch.Joining(documentId, it.key, it) }
        }
        null
    }

    /**
     * What this device can connect with: the keys of the documents (the current one first) and the
     * invitations it joins with; [announced] also the invitations it gives (the owner).
     */
    suspend fun reachable(announced: Boolean): List<TagMatch> = mutex.withLock {
        val states = states()
        buildList {
            for ((documentId, state) in states) {
                if (state.closed) continue
                state.keys.sortedByDescending { it.epoch }.mapNotNull { it.documentKey }.forEach { add(TagMatch.Member(documentId, it)) }
                if (announced) state.invitations.forEach { grant -> DocumentKey.decode(grant.key)?.let { add(TagMatch.Grant(documentId, it, grant)) } }
                val joining = joiningInvitations[documentId] ?: state.joining?.let(Invitation::parse)
                if (state.members == null && joining != null) add(TagMatch.Joining(documentId, joining.key, joining))
            }
        }
    }

    private fun newId(): String = Random.nextLong().toULong().toString(16)

    private fun merge(a: Map<String, Long>, b: Map<String, Long>): Map<String, Long> =
        (a.keys + b.keys).associateWith { maxOf(a[it] ?: 0L, b[it] ?: 0L) }
}
