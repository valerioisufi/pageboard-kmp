package io.github.valerioisufi.drawview.sync

import io.github.valerioisufi.drawview.sync.protocol.DocumentKey
import io.github.valerioisufi.drawview.sync.protocol.Invitation
import io.github.valerioisufi.drawview.sync.protocol.Membership
import io.github.valerioisufi.drawview.sync.protocol.Role
import io.github.valerioisufi.drawview.sync.protocol.SignedMembership
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * An invitation to a document given by its owner: the secret of the link ([key], see
 * [Invitation]), the role of the devices joining with it and whether the owner approves each of
 * them first ([approval]). The owner forgets it to revoke it.
 */
@Serializable
public data class InvitationGrant(val id: String, val key: String, val role: Role, val approval: Boolean, val createdAt: Long)

/** A key of a document and its epoch (see [Membership.epoch]). */
@Serializable
public data class EpochKey(val epoch: Int, val key: String) {
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
public data class SharingState(
    val keys: List<EpochKey> = emptyList(),
    val owner: String = "",
    val membership: SignedMembership? = null,
    val invitations: List<InvitationGrant> = emptyList(),
    val joining: String? = null,
    val versions: Map<String, Map<String, Long>> = emptyMap(),
    /** This device was removed, or the owner stopped sharing the document. */
    val closed: Boolean = false,
    /**
     * The shared folder the document is shared through (see `docs/shared-folders.md`), `null` for a
     * document shared on its own: its keys are derived from the ones of the folder, its owner and
     * membership are the folder's, and they change with the folder only (see `SpaceSharing`).
     */
    // Not written for a document shared on its own: its state stays as it was
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val space: String? = null
) {
    /** The current key. */
    val key: DocumentKey? get() = keys.maxByOrNull { it.epoch }?.documentKey

    val epoch: Int get() = keys.maxOfOrNull { it.epoch } ?: 0

    /** The membership signed by the owner. */
    val members: Membership? get() = membership?.open(owner)

    /** The role of the device [device]. */
    internal fun roleOf(device: String): Role? = members?.memberOf(device)?.role

    public fun encode(): String = json.encodeToString(serializer(), this)

    public companion object {
        // As the membership: the stored states are read again by every version
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        public fun decode(text: String): SharingState? = try {
            json.decodeFromString(serializer(), text)
        } catch (_: Exception) {
            null
        }
    }
}
