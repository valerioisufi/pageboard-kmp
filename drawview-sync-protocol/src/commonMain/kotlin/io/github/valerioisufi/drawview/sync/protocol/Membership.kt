package io.github.valerioisufi.drawview.sync.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What a device may do on a shared document. */
@Serializable
public enum class Role {
    /** Shared the document: admits the others, changes their roles, removes them, ends the sharing. */
    OWNER,
    EDITOR,

    /** Reads the document: its changes are refused by every device. */
    READER
}

/**
 * A device working on a shared document: its identity ([DeviceIdentity.publicKey]), the replica
 * its changes are made by (see `DocumentSession.replica`), its name and its [role].
 *
 * [writesUpTo]: the last of its operations the devices accept when it may not change the
 * document anymore (made reader, or removed): the ones it made before stay, on every device.
 */
@Serializable
public data class Member(
    val device: String,
    val replica: String,
    val name: String,
    val role: Role,
    val writesUpTo: Long? = null
) {
    /** Whether its operation number [sequence] is accepted. */
    public fun mayWrite(sequence: Long): Boolean = writesUpTo?.let { sequence <= it } ?: (role != Role.READER)
}

/**
 * Who works on a shared document (GRASP Information Expert of what each device may do), written
 * and signed by its owner only ([SignedMembership]): every device keeps the most recent one
 * ([version]) and applies it, so that the owner decides on every device without a server.
 *
 * [epoch]: the key of the document in use (a new one each time a device is removed, so that it
 * cannot follow the document anymore, see `SharingState.keys` of `drawview-sync`), [keyHash] its
 * hash ([DocumentKey.hash]: the members take that key only; empty in the memberships of before).
 * [ended]: the owner stopped sharing it, every device keeps its copy only.
 */
@Serializable
public data class Membership(
    val documentId: String,
    val owner: String,
    val version: Long = 1,
    val epoch: Int = 1,
    val members: List<Member> = emptyList(),
    val removed: List<Member> = emptyList(),
    val ended: Boolean = false,
    val keyHash: String = ""
) {
    public fun memberOf(device: String): Member? = members.firstOrNull { it.device == device }

    /** The device making the operations of [replica]: a member's, or a removed one's (see [OperationProof]). */
    public fun deviceOf(replica: String): String? =
        members.firstOrNull { it.replica == replica }?.device ?: removed.lastOrNull { it.replica == replica }?.device

    /** Whether the operation [sequence] of [replica] is accepted (the replicas never members: refused). */
    public fun mayWrite(replica: String, sequence: Long): Boolean {
        members.firstOrNull { it.replica == replica }?.let { return it.mayWrite(sequence) }
        return removed.lastOrNull { it.replica == replica }?.let { sequence <= (it.writesUpTo ?: 0L) } ?: false
    }

    /** Whether [replica] is (or was) a member: its operations can be decided on. */
    public fun knows(replica: String): Boolean = members.any { it.replica == replica } || removed.any { it.replica == replica }

    public fun encode(): ByteArray = json.encodeToString(serializer(), this).encodeToByteArray()

    /** This membership signed by the owner [identity]. */
    public fun signedBy(identity: DeviceIdentity): SignedMembership {
        require(identity.publicKey == owner) { "Only the owner signs the membership" }
        val bytes = encode()
        return SignedMembership(bytes, identity.sign(bytes))
    }

    internal companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

/** A [Membership] as its owner signed it ([bytes], [signature]): it travels as such. */
@Serializable
public class SignedMembership(public val bytes: ByteArray, internal val signature: ByteArray) {
    /** The membership, if signed by [owner] (the owner known for the document), otherwise `null`. */
    public fun open(owner: String): Membership? {
        if (!DeviceIdentity.verify(owner, bytes, signature)) return null
        return try {
            Membership.json.decodeFromString(Membership.serializer(), bytes.decodeToString()).takeIf { it.owner == owner }
        } catch (_: Exception) {
            null
        }
    }

    /** The membership without checking who signed it (the first one of a joiner, see [Invitation.owner]). */
    internal fun peek(): Membership? = try {
        Membership.json.decodeFromString(Membership.serializer(), bytes.decodeToString())
    } catch (_: Exception) {
        null
    }
}
