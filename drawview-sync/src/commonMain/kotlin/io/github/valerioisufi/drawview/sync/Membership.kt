package io.github.valerioisufi.drawview.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What a device may do on a shared document. */
@Serializable
enum class Role {
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
data class Member(
    val device: String,
    val replica: String,
    val name: String,
    val role: Role,
    val writesUpTo: Long? = null
) {
    /** Whether its operation number [sequence] is accepted. */
    fun mayWrite(sequence: Long): Boolean = writesUpTo?.let { sequence <= it } ?: (role != Role.READER)
}

/**
 * Who works on a shared document (GRASP Information Expert of what each device may do), written
 * and signed by its owner only ([SignedMembership]): every device keeps the most recent one
 * ([version]) and applies it, so that the owner decides on every device without a server.
 *
 * [epoch]: the key of the document in use (a new one each time a device is removed, so that it
 * cannot follow the document anymore, see [SharingState.keys]). [ended]: the owner stopped
 * sharing it, every device keeps its copy only.
 */
@Serializable
data class Membership(
    val documentId: String,
    val owner: String,
    val version: Long = 1,
    val epoch: Int = 1,
    val members: List<Member> = emptyList(),
    val removed: List<Member> = emptyList(),
    val ended: Boolean = false
) {
    fun memberOf(device: String): Member? = members.firstOrNull { it.device == device }

    /** Whether the operation [sequence] of [replica] is accepted (the replicas never members: refused). */
    fun mayWrite(replica: String, sequence: Long): Boolean {
        members.firstOrNull { it.replica == replica }?.let { return it.mayWrite(sequence) }
        return removed.lastOrNull { it.replica == replica }?.let { sequence <= (it.writesUpTo ?: 0L) } ?: false
    }

    /** Whether [replica] is (or was) a member: its operations can be decided on. */
    fun knows(replica: String): Boolean = members.any { it.replica == replica } || removed.any { it.replica == replica }

    fun encode(): ByteArray = json.encodeToString(serializer(), this).encodeToByteArray()

    /** This membership signed by the owner [identity]. */
    fun signedBy(identity: DeviceIdentity): SignedMembership {
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
class SignedMembership(val bytes: ByteArray, val signature: ByteArray) {
    /** The membership, if signed by [owner] (the owner known for the document), otherwise `null`. */
    fun open(owner: String): Membership? {
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

/**
 * An invitation to a document given by its owner: the secret of the link ([key], see
 * [Invitation]), the role of the devices joining with it and whether the owner approves each of
 * them first ([approval]). The owner forgets it to revoke it.
 */
@Serializable
data class InvitationGrant(val id: String, val key: String, val role: Role, val approval: Boolean, val createdAt: Long)

/** A key of a document and its epoch (see [Membership.epoch]). */
@Serializable
data class EpochKey(val epoch: Int, val key: String) {
    val documentKey: DocumentKey? get() = DocumentKey.decode(key)
}

/**
 * What this device knows of a shared document (kept by [SharedDocuments]): its keys (the current
 * one and the previous ones, to give the new one to the devices still on them), its owner, the
 * last membership signed by it, the invitations (the owner's), the operations every device has
 * (for the compaction of the log, see [SyncEngine]).
 *
 * A device joining a document has its [joining] invitation only, until the owner admits it.
 */
@Serializable
data class SharingState(
    val keys: List<EpochKey> = emptyList(),
    val owner: String = "",
    val membership: SignedMembership? = null,
    val invitations: List<InvitationGrant> = emptyList(),
    val joining: String? = null,
    val versions: Map<String, Map<String, Long>> = emptyMap(),
    /** This device was removed, or the owner stopped sharing the document. */
    val closed: Boolean = false
) {
    /** The current key. */
    val key: DocumentKey? get() = keys.maxByOrNull { it.epoch }?.documentKey

    val epoch: Int get() = keys.maxOfOrNull { it.epoch } ?: 0

    /** The membership signed by the owner. */
    val members: Membership? get() = membership?.open(owner)

    /** The role of the device [device]. */
    fun roleOf(device: String): Role? = members?.memberOf(device)?.role

    fun encode(): String = Membership.json.encodeToString(serializer(), this)

    companion object {
        fun decode(text: String): SharingState? = try {
            Membership.json.decodeFromString(serializer(), text)
        } catch (_: Exception) {
            null
        }
    }
}
