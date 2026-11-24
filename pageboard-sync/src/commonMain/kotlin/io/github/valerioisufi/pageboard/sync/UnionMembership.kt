package io.github.valerioisufi.pageboard.sync

import io.github.valerioisufi.pageboard.sync.protocol.Member
import io.github.valerioisufi.pageboard.sync.protocol.Membership

/**
 * What the memberships of every access of a document allow together (GRASP Information Expert,
 * see `docs/plans/shared-access.md`): the operations of a replica are taken when the device every
 * membership naming it agrees on signed them ([deviceOf]), and when one of them lets it write
 * ([mayWrite]).
 *
 * Two memberships naming a replica for two different devices give it no device: an honest
 * application never does so (the replica is one per device and store), and the owner of one
 * access cannot make a replica of another access its own, to sign in its name. Its operations
 * wait, as the ones of a replica not known.
 */
internal class UnionMembership(val memberships: List<Membership>) {
    /** Whether [replica] is (or was) a member of some access: its operations can be decided on. */
    fun knows(replica: String): Boolean = memberships.any { it.knows(replica) }

    /** The device every membership naming [replica] names; `null` when none does, or they disagree. */
    fun deviceOf(replica: String): String? = memberships.mapNotNullTo(HashSet()) { it.deviceOf(replica) }.singleOrNull()

    /** Whether the memberships name [replica] for different devices (see the class). */
    fun disagreesOn(replica: String): Boolean = memberships.mapNotNullTo(HashSet()) { it.deviceOf(replica) }.size > 1

    /** Whether the operation [sequence] of [replica] is taken: some access lets it write it. */
    fun mayWrite(replica: String, sequence: Long): Boolean = memberships.any { it.mayWrite(replica, sequence) }

    /** The members of every access, each device once (with its highest role). */
    val members: List<Member>
        get() = memberships.filter { !it.ended }.flatMap { it.members }.groupBy { it.device }.map { (_, same) -> same.minBy { it.role.ordinal } }
}
