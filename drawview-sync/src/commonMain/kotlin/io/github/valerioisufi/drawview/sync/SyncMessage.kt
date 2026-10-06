package io.github.valerioisufi.drawview.sync

import io.github.valerioisufi.drawview.core.change.DocumentSnapshot
import io.github.valerioisufi.drawview.core.change.Operation
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.cbor.Cbor

/** The version of the protocol of the synchronization. */
internal const val PROTOCOL_VERSION = 1

/**
 * The first frame of a connection, in clear: the version of the protocol and the document the
 * connection is about (see [DocumentKey.tag]). Everything else is encrypted with the key of the
 * document (see [SecureChannel]).
 */
@Serializable
internal data class OpenFrame(val protocol: Int, val tag: String)

/**
 * The messages the devices exchange about a document. Each side first says who it is and what it
 * has ([Hello]); then each sends the other what it misses ([Snapshot] for a device without the
 * document, [Operations]), then the operations as they are made; the files of the resources are
 * asked for and sent apart ([ResourceRequest], [ResourceChunk]); where each device is goes
 * along ([PresenceMessage]); a connection silent for a while says it is still there ([Ping]).
 */
@Serializable
internal sealed interface SyncMessage

/**
 * The device [replica] (named [name]) and the operations it has applied ([versionVector]);
 * [hasDocument] is `false` for a device joining a document it does not have yet.
 */
@Serializable
@SerialName("hello")
internal data class Hello(
    val replica: String,
    val name: String,
    val versionVector: Map<String, Long>,
    val hasDocument: Boolean
) : SyncMessage

/** The whole document, for a device that does not have it (its operations follow). */
@Serializable
@SerialName("snapshot")
internal data class Snapshot(val snapshot: DocumentSnapshot) : SyncMessage

/** Operations, in the order they are to be applied (the order of their stamps). */
@Serializable
@SerialName("operations")
internal data class Operations(val operations: List<Operation>) : SyncMessage

/** Asks for the files of the resources [ids]. */
@Serializable
@SerialName("resourceRequest")
internal data class ResourceRequest(val ids: List<String>) : SyncMessage

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
 * inputs of its stroke from [inkFrom] (the ones before were sent already).
 */
@Serializable
@SerialName("presence")
internal data class PresenceMessage(val presence: Presence, val inkFrom: Int = 0) : SyncMessage

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

    fun encodeOpen(frame: OpenFrame): ByteArray = cbor.encodeToByteArray(OpenFrame.serializer(), frame)

    fun decodeOpen(frame: ByteArray): OpenFrame? = try {
        cbor.decodeFromByteArray(OpenFrame.serializer(), frame)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
