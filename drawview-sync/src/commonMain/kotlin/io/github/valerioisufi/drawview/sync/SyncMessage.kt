package io.github.valerioisufi.drawview.sync

import io.github.valerioisufi.drawview.model.change.DocumentSnapshot
import io.github.valerioisufi.drawview.model.change.Operation
import io.github.valerioisufi.drawview.sync.protocol.DeviceIdentity
import io.github.valerioisufi.drawview.sync.protocol.Membership
import io.github.valerioisufi.drawview.sync.protocol.SealedOperation
import io.github.valerioisufi.drawview.sync.protocol.SignedMembership
import io.github.valerioisufi.drawview.sync.protocol.SyncException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.cbor.Cbor

/**
 * The messages the devices exchange about a document. Each side first challenges the other one
 * ([Challenge]) and says who it is, proving it, and what it has ([Hello]); the membership signed
 * by the owner goes to the side with an older one ([MembershipMessage]), and only the members go
 * on (a device joining with an invitation is admitted by the owner, [Admission], or refused,
 * [JoinRefused]); then each sends the other what it misses ([Snapshot] for a device without the
 * document, [Operations]), then the operations as they are made; the files of the resources are
 * asked for and sent apart ([ResourceRequest], [ResourceChunk]); where each device is goes
 * along ([PresenceMessage]); a connection silent for a while says it is still there ([Ping]).
 */
@Serializable
internal sealed interface SyncMessage

/** A random [nonce] the other device signs in its [Hello] (a recorded hello cannot be replayed). */
@Serializable
@SerialName("challenge")
internal class Challenge(val nonce: ByteArray) : SyncMessage

/**
 * The device [device] ([DeviceIdentity.publicKey], [proof]: its signature of the challenge of the
 * other device, see [helloProof]) of replica [replica] named [name], with the operations it has
 * applied ([versionVector]); [hasDocument] is `false` for a device joining a document it does not
 * have yet. [membershipVersion] and [epoch]: the membership and the key it has.
 */
@Serializable
@SerialName("hello")
internal class Hello(
    val replica: String,
    val name: String,
    val versionVector: Map<String, Long>,
    val hasDocument: Boolean,
    val device: String,
    val proof: ByteArray,
    val membershipVersion: Long = 0,
    val epoch: Int = 0
) : SyncMessage

/** The bytes a device signs in its [Hello]: the [nonce] of the other one, the [tag] of the connection and who it is. */
internal fun helloProof(nonce: ByteArray, tag: String, replica: String, device: String): ByteArray =
    "drawview-sync/hello|$tag|$replica|$device|".encodeToByteArray() + nonce

/** The membership of the document as signed by its owner (see [Membership]). */
@Serializable
@SerialName("membership")
internal class MembershipMessage(val membership: SignedMembership) : SyncMessage

/** Asks for the membership, more recent than the [version] of this device (e.g. operations of a device it does not know). */
@Serializable
@SerialName("membershipQuery")
internal data class MembershipQuery(val version: Long) : SyncMessage

/** The owner admits the device joining: the key of the document ([epoch]) and the membership. */
@Serializable
@SerialName("admission")
internal class Admission(val epoch: Int, val key: String, val membership: SignedMembership) : SyncMessage

/** The owner refused the device joining. */
@Serializable
@SerialName("joinRefused")
internal data object JoinRefused : SyncMessage

/** The new key of the document ([epoch]), for a member still on an older one. */
@Serializable
@SerialName("key")
internal data class KeyUpdate(val epoch: Int, val key: String) : SyncMessage

/** The device leaves the document (the owner removes it). */
@Serializable
@SerialName("leave")
internal data object Leave : SyncMessage

/** The operations every member has, as far as this device knows (by [DeviceIdentity.publicKey]). */
@Serializable
@SerialName("versions")
internal data class KnownVersions(val versions: Map<String, Map<String, Long>>) : SyncMessage

/**
 * The whole document, for a device that does not have it: with the operations [base] whose
 * changes it holds and that the log no longer has (see `DocumentStore.compactOperations`); the
 * operations after them follow.
 */
@Serializable
@SerialName("snapshot")
internal data class Snapshot(val snapshot: DocumentSnapshot, val base: Map<String, Long> = emptyMap()) : SyncMessage

/**
 * Operations, in the order they are to be applied (the order of their stamps), sealed with the key
 * of the document: what a relay could keep without reading it (see [SealedOperation]).
 */
@Serializable
@SerialName("operations")
internal class Operations(val operations: List<SealedOperation>) : SyncMessage

/** Asks for the file of the resource [id], from its byte [from] (a transfer interrupted goes on). */
@Serializable
internal data class ResourceAsk(val id: String, val from: Long = 0)

/** Asks for the files of the resources [asks]. */
@Serializable
@SerialName("resourceRequest")
internal data class ResourceRequest(val asks: List<ResourceAsk>) : SyncMessage

/**
 * A part of the file of the resource [id] ([total] bytes, named with [extension]): its bytes from
 * [offset].
 */
@Serializable
@SerialName("resourceChunk")
internal class ResourceChunk(
    val id: String,
    val extension: String,
    val offset: Long,
    val total: Long,
    val bytes: ByteArray
) : SyncMessage

/** The file of the resource [id] is not on the device asked for it. */
@Serializable
@SerialName("resourceMissing")
internal data class ResourceMissing(val id: String) : SyncMessage

/**
 * Where a device is on the document (never stored, see [Presence]): its [presence] has the
 * inputs of its stroke from [inkFrom] (the ones before were sent already), not the text it is
 * writing when [textKept] nor the elements it selected when [selectionKept] (the same as before).
 */
@Serializable
@SerialName("presence")
internal data class PresenceMessage(
    val presence: Presence,
    val inkFrom: Int = 0,
    val textKept: Boolean = false,
    val selectionKept: Boolean = false
) : SyncMessage

/**
 * The items beside the document this device has ([io.github.valerioisufi.drawview.model.DocumentAttachments]),
 * with their versions: all of them once the operations are exchanged, then each one as it changes.
 */
@Serializable
@SerialName("attachments")
internal data class AttachmentVersions(val versions: Map<String, String>) : SyncMessage

/** Asks for the item [key] at [version] from its byte [from] (a transfer interrupted goes on). */
@Serializable
internal data class AttachmentAsk(val key: String, val version: String, val from: Long = 0)

/** Asks for the items [asks] beside the document. */
@Serializable
@SerialName("attachmentRequest")
internal data class AttachmentRequest(val asks: List<AttachmentAsk>) : SyncMessage

/** A part of the item [key] beside the document at [version] ([size] bytes): its bytes from [offset]. */
@Serializable
@SerialName("attachmentPart")
internal class AttachmentPart(
    val key: String,
    val version: String,
    val offset: Long,
    val size: Long,
    val bytes: ByteArray
) : SyncMessage

/** A connection with nothing to send says it is still there (see [SyncEngine]). */
@Serializable
@SerialName("ping")
internal data object Ping : SyncMessage

/** The binary form of the frames (CBOR). */
@OptIn(ExperimentalSerializationApi::class)
internal object SyncCodec {
    private val cbor = Cbor { ignoreUnknownKeys = true }

    fun encode(message: SyncMessage): ByteArray = cbor.encodeToByteArray(SyncMessage.serializer(), message)

    /** The message of [frame], or `null` for a message this version does not know. */
    fun decode(frame: ByteArray): SyncMessage? = try {
        decodeOrThrow(frame)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    fun decodeOrThrow(frame: ByteArray): SyncMessage = cbor.decodeFromByteArray(SyncMessage.serializer(), frame)

    /** The content of a [SealedOperation]. */
    fun encodeOperation(operation: Operation): ByteArray = cbor.encodeToByteArray(Operation.serializer(), operation)

    fun decodeOperation(bytes: ByteArray): Operation = try {
        cbor.decodeFromByteArray(Operation.serializer(), bytes)
    } catch (e: SerializationException) {
        throw SyncException("An operation not understood", e)
    } catch (e: IllegalArgumentException) {
        throw SyncException("An operation not understood", e)
    }
}
