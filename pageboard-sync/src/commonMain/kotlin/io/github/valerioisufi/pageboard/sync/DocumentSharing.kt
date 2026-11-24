package io.github.valerioisufi.pageboard.sync

import io.github.valerioisufi.pageboard.platform.currentTimeMillis
import io.github.valerioisufi.pageboard.sync.protocol.DeviceIdentity
import io.github.valerioisufi.pageboard.sync.protocol.DocumentKey
import io.github.valerioisufi.pageboard.sync.protocol.Grant
import io.github.valerioisufi.pageboard.sync.protocol.Invitation
import io.github.valerioisufi.pageboard.sync.protocol.Member
import io.github.valerioisufi.pageboard.sync.protocol.Membership
import io.github.valerioisufi.pageboard.sync.protocol.Placement
import io.github.valerioisufi.pageboard.sync.protocol.MembershipKind
import io.github.valerioisufi.pageboard.sync.protocol.Role
import io.github.valerioisufi.pageboard.sync.protocol.SignedMembership
import io.github.valerioisufi.pageboard.sync.protocol.SpaceRef
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

    /**
     * The access of the document the connection is about (see [DocumentAccess]): the shared folder
     * it goes through, `null` for the document's own sharing (or the own sharing of a shared folder).
     */
    val via: String? get() = null

    /** A key of the document (of any epoch) through [via]: between members of that access. */
    data class Member(override val documentId: String, override val key: DocumentKey, override val via: String? = null) : TagMatch

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
 *
 * A document may be reached several ways ([DocumentAccess], see `docs/plans/shared-access.md`):
 * its own sharing and the shared folders it is in. Each change is of one access ([via]: the shared
 * folder, `null` for its own sharing), the others left as they are; what they give together (the
 * role of a device, who may write an operation) is their union ([unionOf]).
 */
internal class DocumentSharing(
    private val shared: SharedDocuments,
    val identity: DeviceIdentity,
    /** What the memberships kept here are of: documents, or shared folders (see [SpaceSharing]). */
    private val kind: MembershipKind = MembershipKind.DOCUMENT
) {
    private val mutex = Mutex()
    private var cache: MutableMap<String, DocumentAccess>? = null
    private val joiningInvitations = mutableMapOf<String, Invitation>()

    /** The memberships of every access of each document (under [mutex]), each signature checked once, when it changes. */
    private val opened = mutableMapOf<String, Map<String?, Membership>>()

    /** The memberships learned of the accesses this device is not in (under [mutex]), checked once too. */
    private val learnedOpened = mutableMapOf<String, Map<String?, Membership>>()

    private val membershipsState = MutableStateFlow<Map<String, Membership>>(emptyMap())

    /**
     * The memberships of the shared documents, by document: of its own sharing, else of its
     * innermost shared folder (every one: [unionOf], [allMemberships]).
     */
    val memberships: StateFlow<Map<String, Membership>> = membershipsState.asStateFlow()

    private val changedFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /** Documents whose keys or invitations changed (what to announce and connect with). */
    val changed: SharedFlow<String> = changedFlow

    val device: String get() = identity.publicKey

    private suspend fun accesses(): MutableMap<String, DocumentAccess> =
        cache ?: shared.all().mapValuesTo(mutableMapOf()) { DocumentAccess.decode(it.value) }.also { all ->
            cache = all
            for ((documentId, access) in all) open(documentId, access)
            membershipsState.value = opened.mapNotNull { (id, memberships) -> memberships.values.firstOrNull()?.let { id to it } }.toMap()
        }

    /** The memberships of the accesses of [documentId] checked (each one is a signature to check). */
    private fun open(documentId: String, access: DocumentAccess?) {
        val memberships = access?.all?.mapNotNull { state -> state.members?.let { state.space to it } }?.toMap().orEmpty()
        if (memberships.isEmpty()) opened.remove(documentId) else opened[documentId] = memberships
        val learned = access?.learned?.mapNotNull { state -> state.members?.let { state.space to it } }?.toMap().orEmpty()
        if (learned.isEmpty()) learnedOpened.remove(documentId) else learnedOpened[documentId] = learned
    }

    /** Every access of [documentId], `null` when it is not shared. */
    suspend fun accessOf(documentId: String): DocumentAccess? = mutex.withLock { accesses()[documentId] }

    /** The access of [documentId] through [via] (`null`: its own sharing). */
    suspend fun stateOf(documentId: String, via: String?): SharingState? = mutex.withLock { accesses()[documentId]?.of(via) }

    /** The first access of [documentId]: its own sharing, else its innermost shared folder (the only one of a shared folder's own sharing). */
    suspend fun stateOf(documentId: String): SharingState? = mutex.withLock { accesses()[documentId]?.all?.firstOrNull() }

    /** Every document shared, with its accesses. */
    suspend fun allAccesses(): Map<String, DocumentAccess> = mutex.withLock { accesses().toMap() }

    /** The first access of every document shared (see [stateOf]). */
    suspend fun all(): Map<String, SharingState> = mutex.withLock { accesses().mapNotNull { (id, access) -> access.all.firstOrNull()?.let { id to it } }.toMap() }

    /** The accesses through the shared folder [spaceId], by document. */
    suspend fun through(spaceId: String): Map<String, SharingState> =
        mutex.withLock { accesses().mapNotNull { (id, access) -> access.of(spaceId)?.let { id to it } }.toMap() }

    /** The membership of the access of [documentId] through [via] (signed by its owner), its signature checked once. */
    suspend fun membershipOf(documentId: String, via: String?): Membership? = mutex.withLock {
        accesses()
        opened[documentId]?.get(via)
    }

    /** The membership of the first access of [documentId] (see [memberships]). */
    suspend fun membershipOf(documentId: String): Membership? = mutex.withLock {
        accesses()
        membershipsState.value[documentId]
    }

    /**
     * What the memberships of every access of [documentId] allow together, the ones learned of the
     * accesses this device is not in included; `null` when it has none.
     */
    suspend fun unionOf(documentId: String): UnionMembership? = mutex.withLock {
        accesses()
        val mine = opened[documentId] ?: return null
        val learned = learnedOpened[documentId].orEmpty()
        // A shared folder the document left: its members' operations taken up to where its owner said
        val own = mine[null] ?: learned[null]
        val ended = learned.mapNotNull { (via, membership) -> own?.spaces?.firstOrNull { it.space == via }?.writesUpTo?.let { membership to it } }.toMap()
        UnionMembership(mine.values.toList() + learned.values, ended)
    }

    /** The membership learned of the access of [documentId] through [via] this device is not in. */
    suspend fun learnedOf(documentId: String, via: String?): Membership? = mutex.withLock {
        accesses()
        learnedOpened[documentId]?.get(via)
    }

    /**
     * Keeps [signed] (by [owner]) as the membership of the access of [documentId] through [via]
     * this device is not in, when it is more recent than the one learned: what is learned is
     * anchored by the caller (see `SyncEngine.isAnchored`). Nothing for an access of this device.
     *
     * @return whether it was kept.
     */
    suspend fun learn(documentId: String, via: String?, owner: String, signed: SignedMembership, placement: Placement? = null): Boolean {
        val membership = signed.open(owner) ?: return false
        var kept = false
        edit(documentId) { access ->
            if (access.isEmpty || access.of(via) != null) return@edit access
            val before = access.learnedOf(via)?.members?.version ?: 0
            if (membership.version <= before) return@edit access
            kept = true
            access.with(SharingState(owner = owner, membership = signed, space = via, learned = true, placement = placement))
        }
        return kept
    }

    /** The membership of every access of every document. */
    suspend fun allMemberships(): List<Membership> = mutex.withLock {
        accesses()
        opened.values.flatMap { it.values }
    }

    /**
     * Changes the access of [documentId] through [via] by [transform] (`null`: that access no
     * more); the new one. The document is shared no more once it has no access left.
     */
    private suspend fun update(documentId: String, via: String?, transform: (SharingState?) -> SharingState?): SharingState? {
        var updated: SharingState? = null
        edit(documentId) { before ->
            updated = transform(before.of(via))?.let { if (it.space == via && !it.learned) it else it.copy(space = via, learned = false) }
            updated?.let { before.with(it) } ?: before.without(via)
        }
        return updated
    }

    /** Changes the accesses of [documentId] by [transform], and keeps them (the document shared no more once it has none). */
    private suspend fun edit(documentId: String, transform: (DocumentAccess) -> DocumentAccess): Unit = mutex.withLock {
        val accesses = accesses()
        val before = accesses[documentId] ?: DocumentAccess()
        val access = transform(before)
        // The memberships of this document only checked again
        open(documentId, access)
        val memberships = opened[documentId]
        val first = memberships?.values?.firstOrNull()
        membershipsState.value = if (first == null) membershipsState.value - documentId else membershipsState.value + (documentId to first)
        val kept = access.encode()
        if (kept == null) {
            accesses.remove(documentId)
            shared.remove(documentId)
        } else {
            accesses[documentId] = access
            // Read only when no access lets this device write
            val role = memberships?.values?.mapNotNull { it.memberOf(device)?.role }?.minByOrNull { it.ordinal }
            shared.save(documentId, kept, readOnly = role == Role.READER)
        }
    }.also { changedFlow.tryEmit(documentId) }

    /** Saves every access of [documentId] again (e.g. the document received: kept with it). */
    suspend fun saveAgain(documentId: String) {
        mutex.withLock {
            val access = accesses()[documentId] ?: return
            val kept = access.encode() ?: return
            val role = opened[documentId]?.values?.mapNotNull { it.memberOf(device)?.role }?.minByOrNull { it.ordinal }
            shared.save(documentId, kept, readOnly = role == Role.READER)
        }
        changedFlow.tryEmit(documentId)
    }

    // ==========================================================
    // The owner
    // ==========================================================

    /** Shares [documentId] (this device becomes its owner), unless it is shared already. */
    suspend fun share(documentId: String, replica: String, name: String): SharingState = update(documentId, null) { state ->
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
        val state = update(documentId, null) { state -> if (state?.owner != device) state else state.copy(invitations = state.invitations + grant) }
        return grant.takeIf { state?.invitations?.contains(grant) == true }
    }

    /** Revokes the invitation [grantId]: nobody joins with it anymore. */
    suspend fun revoke(documentId: String, grantId: String) {
        update(documentId, null) { state -> state?.copy(invitations = state.invitations.filter { it.id != grantId }) }
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
        ).keepingGrantsOf(membership) { lastSequence }
    }

    /**
     * Gives [grants] to the members of [documentId] (this device is its owner). A member losing a
     * capability keeps what it did with it up to its last operation this device has
     * ([lastSequences], by replica: see [Grant.upTo]).
     */
    suspend fun setGrants(documentId: String, grants: List<Grant>, lastSequences: Map<String, Long>): SharingState? = ownerChange(documentId) { membership ->
        membership.copy(grants = grants).keepingGrantsOf(membership) { lastSequences[it.replica] ?: 0L }
    }

    /**
     * Names [ref] among the shared folders the item of [documentId] is in (this device is the owner
     * of its own sharing, see [Membership.spaces]): in place of the one of the same folder.
     */
    suspend fun nameSpace(documentId: String, ref: SpaceRef): SharingState? = ownerChange(documentId) { membership ->
        if (ref in membership.spaces) return@ownerChange null
        membership.copy(spaces = membership.spaces.filter { it.space != ref.space } + ref)
    }

    /** Keeps [placement], made by this device, with the own sharing of [documentId] (given with its membership to the folder it names). */
    suspend fun keepPlacement(documentId: String, placement: Placement) {
        update(documentId, null) { state -> state?.copy(placement = placement) }
    }

    /**
     * The members of [this] that held a capability in [before] and no longer do keep what they did
     * with it, up to their last operation ([lastSequenceOf]): its [Grant.upTo], the ones of before
     * kept.
     */
    private fun Membership.keepingGrantsOf(before: Membership, lastSequenceOf: (Member) -> Long): Membership {
        var kept = grants
        for (capability in (before.grants + grants).map { it.capability }.distinct()) {
            val old = before.grantsOf(capability)
            val now = kept.filter { it.capability == capability }
            val lost = before.members.filter { member ->
                member.device != before.owner && old.any { it.covers(member) } &&
                    (members.firstOrNull { it.device == member.device }?.let { current -> now.none { it.covers(current) } } ?: true)
            }
            if (lost.isEmpty() && old.none { it.upTo.isNotEmpty() }) continue
            val upTo = mutableMapOf<String, Long>()
            for (grant in old + now) for ((replica, sequence) in grant.upTo) upTo[replica] = maxOf(upTo[replica] ?: 0L, sequence)
            for (member in lost) upTo[member.replica] = maxOf(upTo[member.replica] ?: 0L, lastSequenceOf(member))
            // Kept on the first grant of the capability, or on one of its own when none is left
            val withUpTo = if (now.isEmpty()) listOf(Grant(capability, upTo = upTo)) else listOf(now.first().copy(upTo = upTo)) + now.drop(1).map { it.copy(upTo = emptyMap()) }
            kept = kept.filter { it.capability != capability } + withUpTo
        }
        return copy(grants = kept)
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
            ).keepingGrantsOf(membership) { lastSequence }
        }
    }

    /** Ends the sharing of [documentId] for every device (each one keeps its copy). */
    suspend fun end(documentId: String): SharingState? = ownerChange(documentId) { it.copy(ended = true) }

    /** A change of the membership of the own sharing of [documentId] by its owner, signed: [newKey] for the new epoch it makes. */
    private suspend fun ownerChange(documentId: String, newKey: String? = null, transform: (Membership) -> Membership?): SharingState? {
        var changed = false
        val state = update(documentId, null) { state ->
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

    /** Keeps [signed] for the access through [via] if it is more recent and signed by its owner: the new membership, otherwise `null`. */
    suspend fun adopt(documentId: String, via: String?, signed: SignedMembership): Membership? {
        var adopted: Membership? = null
        update(documentId, via) { state ->
            val membership = signed.open(state?.owner ?: return@update state) ?: return@update state
            if (!isOf(membership, documentId, state) || membership.version <= (state.members?.version ?: 0)) return@update state
            adopted = membership
            state.copy(membership = signed)
        }
        return adopted
    }

    /**
     * Keeps the key of [epoch] of the own sharing of [documentId] (given by a member: up to the
     * epoch of the membership); when the membership names its key ([Membership.keyHash]), that one
     * only. The keys of an access through a shared folder come from the folder.
     */
    suspend fun adoptKey(documentId: String, via: String?, epoch: Int, key: String): Boolean {
        if (via != null) return false
        var adopted = false
        update(documentId, null) { state ->
            val decoded = DocumentKey.decode(key)
            if (state == null || state.space != null || decoded == null || state.keys.any { it.epoch == epoch }) return@update state
            val members = state.members
            if (epoch > (members?.epoch ?: 0)) return@update state
            if (members != null && members.keyHash.isNotEmpty() && (epoch != members.epoch || decoded.hash != members.keyHash)) return@update state
            adopted = true
            state.copy(keys = state.keys + EpochKey(epoch, key))
        }
        return adopted
    }

    /** Joins [invitation] (the own sharing of its document): the owner admits this device when it reaches it. */
    suspend fun startJoining(invitation: Invitation) {
        mutex.withLock { joiningInvitations[invitation.documentId] = invitation }
        update(invitation.documentId, null) { state -> if (state?.members != null) state else SharingState(joining = invitation.toLink()) }
    }

    /** The owner [owner] admitted this device to the own sharing of [documentId]: its key and the membership. */
    suspend fun admitted(documentId: String, owner: String, epoch: Int, key: String, signed: SignedMembership): Membership? {
        val membership = signed.open(owner)?.takeIf { it.documentId == documentId && it.kind == kind && it.memberOf(device) != null } ?: return null
        if (DocumentKey.decode(key) == null) return null
        mutex.withLock { joiningInvitations.remove(documentId) }
        update(documentId, null) { SharingState(keys = listOf(EpochKey(epoch, key)), owner = owner, membership = signed) }
        return membership
    }

    /**
     * Keeps [state] as the access of [documentId] through the shared folder [SharingState.space]
     * (its keys, owner and membership the folder's), with what this device knows of its members'
     * operations ([SharingState.versions]) kept; the document's other accesses as they are.
     */
    suspend fun follow(documentId: String, state: SharingState): SharingState? {
        val via = state.space ?: return null
        // The same already (most of the time): nothing written
        stateOf(documentId, via)?.let { previous ->
            if (previous == state.copy(versions = previous.versions)) return previous
        }
        return update(documentId, via) { previous -> state.copy(versions = previous?.versions.orEmpty(), through = emptyList()) }
    }

    /** Whether [membership] is the one of [documentId]: its own, or the one of its shared folder. */
    private fun isOf(membership: Membership, documentId: String, state: SharingState): Boolean = when (val space = state.space) {
        null -> membership.documentId == documentId && membership.kind == kind
        else -> membership.documentId == space && membership.kind == MembershipKind.SPACE
    }

    /** This device does not reach [documentId] through [via] anymore (it left, was removed, the sharing ended, the document left the folder). */
    suspend fun forget(documentId: String, via: String?) {
        if (via == null) mutex.withLock { joiningInvitations.remove(documentId) }
        update(documentId, via) { null }
    }

    /** This device does not share [documentId] anymore, through any access. */
    suspend fun forget(documentId: String) {
        val access = accessOf(documentId) ?: run {
            mutex.withLock { joiningInvitations.remove(documentId) }
            return
        }
        for (state in access.all) forget(documentId, state.space)
    }

    /**
     * Remembers the operations the members of the access through [via] have ([versions], by
     * device): the log is compacted with them.
     */
    suspend fun recordVersions(documentId: String, via: String?, versions: Map<String, Map<String, Long>>) {
        update(documentId, via) { state ->
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
        for ((documentId, access) in accesses()) {
            for (state in access.all) {
                if (state.closed) continue
                state.keys.mapNotNull { it.documentKey }.firstOrNull { it.tag == tag }?.let { return TagMatch.Member(documentId, it, state.space) }
                if (state.space != null) continue
                for (grant in state.invitations) {
                    val key = DocumentKey.decode(grant.key) ?: continue
                    if (key.tag == tag) return TagMatch.Grant(documentId, key, grant)
                }
            }
            joiningOf(documentId, access)?.takeIf { it.key.tag == tag }?.let { return TagMatch.Joining(documentId, it.key, it) }
        }
        null
    }

    /** The invitation this device joins the own sharing of [documentId] with, while it is not admitted. */
    private fun joiningOf(documentId: String, access: DocumentAccess): Invitation? {
        val own = access.own
        if (own?.members != null) return null
        return joiningInvitations[documentId] ?: own?.joining?.let(Invitation::parse)
    }

    /**
     * What this device can connect with: the keys of every access of the documents (the current one
     * first) and the invitations it joins with; [announced] also the invitations it gives (the
     * owner).
     */
    suspend fun reachable(announced: Boolean): List<TagMatch> = mutex.withLock {
        val accesses = accesses()
        buildList {
            for ((documentId, access) in accesses) {
                for (state in access.all) {
                    if (state.closed) continue
                    state.keys.sortedByDescending { it.epoch }.mapNotNull { it.documentKey }.forEach { add(TagMatch.Member(documentId, it, state.space)) }
                    if (announced && state.space == null) state.invitations.forEach { grant -> DocumentKey.decode(grant.key)?.let { add(TagMatch.Grant(documentId, it, grant)) } }
                }
                joiningOf(documentId, access)?.let { add(TagMatch.Joining(documentId, it.key, it)) }
            }
        }
    }

    private fun newId(): String = Random.nextLong().toULong().toString(16)

    private fun merge(a: Map<String, Long>, b: Map<String, Long>): Map<String, Long> =
        (a.keys + b.keys).associateWith { maxOf(a[it] ?: 0L, b[it] ?: 0L) }
}
