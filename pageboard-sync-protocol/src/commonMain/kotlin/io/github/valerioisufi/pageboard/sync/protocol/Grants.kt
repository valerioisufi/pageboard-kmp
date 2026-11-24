package io.github.valerioisufi.pageboard.sync.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString

/**
 * The capabilities the library understands (see [Grant] and `docs/plans/shared-access.md`). An
 * application may grant capabilities of its own, under a name of its own: the engine carries them
 * signed and never reads them.
 */
public object Capability {
    /** Sharing an item of a shared folder on its own while it stays inside (a folder as a new shared folder, a document with a sharing of its own). */
    public const val SHARE_INSIDE: String = "pageboard.share-inside"

    /** Bringing into a shared folder an item that has a sharing of its own. */
    public const val BRING_SHARED: String = "pageboard.bring-shared"

    /** Putting the item of this sharing into a shared folder of the member's own, without its owner ([Placement]). */
    public const val PLACE_IN_SPACES: String = "pageboard.place-in-spaces"
}

/**
 * A capability given by the owner of a sharing to some of its members (GRASP Information Expert of
 * what a member may do beyond its role), signed with the [Membership]: the members of at least
 * [role], and the devices named one by one ([devices]).
 *
 * A grant only allows: a capability no grant gives is the owner's alone, so a device that does not
 * know a capability refuses what it would allow, and never allows what the owner did not give.
 * [upTo]: for a replica that held it and no longer does, the last of its operations it covered (as
 * [Member.writesUpTo] for a role), so that every device admits the same changes whatever the order
 * the memberships arrived in.
 */
@Serializable
public data class Grant(
    val capability: String,
    val role: Role? = null,
    val devices: List<String> = emptyList(),
    val upTo: Map<String, Long> = emptyMap()
) {
    /** Whether [member] holds this grant now: named, or of at least its role. */
    public fun covers(member: Member): Boolean = member.device in devices || role != null && member.role.ordinal <= role.ordinal
}

/**
 * A shared folder an item is in, as the owner of the item's own sharing names it (see
 * [Membership.spaces]): the identifier of the folder ([space]) and the device of its owner
 * ([owner]). The members of the item's sharing take that folder's membership from [owner] only.
 * [writesUpTo]: once the item left the folder, the last operation of each replica of its members
 * the item's members take (as for a member removed); `null` while it is in.
 */
@Serializable
public data class SpaceRef(val space: String, val owner: String, val writesUpTo: Map<String, Long>? = null)

/**
 * A member of a sharing putting its item into a shared folder of its own ([ref]), signed by its
 * device ([device]) while the owner of the sharing may be away: taken when the membership of the
 * sharing grants that device [Capability.PLACE_IN_SPACES].
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
public class Placement(
    public val itemId: String,
    public val ref: SpaceRef,
    public val device: String,
    @ByteString public val signature: ByteArray
) {
    /** Whether [device] signed it. */
    public fun isSigned(): Boolean = DeviceIdentity.verify(device, bytes(itemId, ref), signature)

    /** Whether it is taken for the item of [membership]: signed, of that item, by a member holding [Capability.PLACE_IN_SPACES]. */
    public fun isAllowedBy(membership: Membership): Boolean {
        if (itemId != membership.documentId || !isSigned()) return false
        val member = membership.memberOf(device) ?: return false
        return membership.holds(Capability.PLACE_IN_SPACES, member.replica, Long.MAX_VALUE)
    }

    public companion object {
        /** [itemId] put into the folder of [ref] by [identity]. */
        public fun sign(identity: DeviceIdentity, itemId: String, ref: SpaceRef): Placement =
            Placement(itemId, ref, identity.publicKey, identity.sign(bytes(itemId, ref)))

        private fun bytes(itemId: String, ref: SpaceRef): ByteArray =
            "pageboard-sync/placement|${itemId.length}:$itemId|${ref.space.length}:${ref.space}|${ref.owner}".encodeToByteArray()
    }
}
