package io.github.valerioisufi.pageboard.sync

import io.github.valerioisufi.pageboard.sync.protocol.Grant
import io.github.valerioisufi.pageboard.sync.protocol.Membership
import io.github.valerioisufi.pageboard.sync.protocol.Role

/**
 * Who has a document or a shared folder, and through what (see [SyncEngine.accessOf] and
 * `docs/plans/shared-access.md`): its own sharing and the shared folders around it ([sources]),
 * each with its members and the grants of its owner, and what they give together ([members], the
 * [role] of this device).
 *
 * What an application shows of the sharing of an item comes from here: where each person's
 * access comes from, what removing them from one source leaves them.
 */
public class ItemAccess internal constructor(
    /** The document, or the shared folder. */
    public val itemId: String,
    /** Its own sharing first (if any), then the shared folders around it, innermost first. */
    public val sources: List<AccessSource>,
    /** The device this engine is. */
    private val device: String
) {
    /** Every member of some source, once each, with its highest role and its sources. */
    public val members: List<AccessMember>
        get() = sources.flatMap { source -> source.membership.members.map { it to source } }
            .groupBy { it.first.device }
            .map { (device, held) ->
                val best = held.minBy { it.first.role.ordinal }.first
                AccessMember(device, best.name, best.role, held.map { it.second })
            }

    /** The highest role of this device, `null` when it is a member of no source. */
    public val role: Role? get() = members.firstOrNull { it.device == device }?.role

    /** Whether this device may only read the item: a member, and allowed to write by no source. */
    public val isReadOnly: Boolean get() = role == Role.READER

    /** What [device] keeps without [source]: its highest role through the others, `null` when none. */
    public fun withoutSource(device: String, source: AccessSource): Role? =
        sources.filter { it !== source }.mapNotNull { it.membership.memberOf(device)?.role }.minByOrNull { it.ordinal }
}

/** One way an item is shared (see [ItemAccess]): its own sharing, or a shared folder around it. */
public sealed interface AccessSource {
    /** Its membership, signed by its owner. */
    public val membership: Membership

    /** What its owner allows some members beyond their roles. */
    public val grants: List<Grant> get() = membership.grants

    /** Its owner. */
    public val owner: String get() = membership.owner

    /** The item's own sharing (of a document, or of a shared folder itself). */
    public class Own internal constructor(override val membership: Membership) : AccessSource

    /** The shared folder [spaceId] the item is in. */
    public class Space internal constructor(public val spaceId: String, override val membership: Membership) : AccessSource
}

/** A member of an item through one or more [sources], with its highest [role] among them. */
public class AccessMember internal constructor(
    public val device: String,
    public val name: String,
    public val role: Role,
    public val sources: List<AccessSource>
)
