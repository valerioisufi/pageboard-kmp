package io.github.valerioisufi.pageboard.sync

import io.github.valerioisufi.pageboard.sync.protocol.Role

/**
 * Every way this device reaches a document (GRASP Information Expert of what they give
 * together, see `docs/plans/shared-access.md`): its own sharing ([own]) and the sharing of each
 * shared folder it is in ([spaces], innermost first), each one kept whole (its keys, owner,
 * membership, invitations, the versions of its members).
 *
 * Nothing of the accesses is merged in what is kept: their union is computed when it is read. A
 * member removed from one access, or a sharing ended, leaves the others as they are.
 *
 * It is kept as one [SharingState] ([encode], [decode]): the first access as the state it is (its
 * own sharing, else its innermost folder), the others in its [SharingState.through]. A state kept
 * before is one access.
 */
internal data class DocumentAccess(val own: SharingState? = null, val spaces: List<SharingState> = emptyList()) {
    /** Every access, its own sharing first. */
    val all: List<SharingState> get() = listOfNotNull(own) + spaces

    val isEmpty: Boolean get() = own == null && spaces.isEmpty()

    /** The access of [space] (`null`: the document's own sharing). */
    fun of(space: String?): SharingState? = if (space == null) own else spaces.firstOrNull { it.space == space }

    /** With [state] as the access of its [SharingState.space] (added, or in place of the one there). */
    fun with(state: SharingState): DocumentAccess {
        val access = state.copy(through = emptyList())
        val space = access.space ?: return copy(own = access)
        val index = spaces.indexOfFirst { it.space == space }
        return copy(spaces = if (index < 0) spaces + access else spaces.toMutableList().also { it[index] = access })
    }

    /** Without the access of [space] (`null`: the document's own sharing). */
    fun without(space: String?): DocumentAccess = if (space == null) copy(own = null) else copy(spaces = spaces.filter { it.space != space })

    /** The highest role of [device] among the accesses (`null`: a member of none). */
    fun roleOf(device: String): Role? = all.mapNotNull { it.roleOf(device) }.minByOrNull { it.ordinal }

    /** Whether [device] may only read the document: a member of some access, and allowed to write by none. */
    fun isReadOnly(device: String): Boolean = roleOf(device) == Role.READER

    /** As it is kept: the first access, the others in its [SharingState.through]; `null` for no access. */
    fun encode(): SharingState? {
        val accesses = all
        val first = accesses.firstOrNull() ?: return null
        return first.copy(through = accesses.drop(1))
    }

    companion object {
        /** The accesses kept as [state] (a state of before: one access). */
        fun decode(state: SharingState): DocumentAccess {
            val accesses = listOf(state.copy(through = emptyList())) + state.through.map { it.copy(through = emptyList()) }
            return accesses.fold(DocumentAccess()) { access, next ->
                // One access per sharing: the first one kept
                if (access.of(next.space) != null) access else access.with(next)
            }
        }
    }
}
