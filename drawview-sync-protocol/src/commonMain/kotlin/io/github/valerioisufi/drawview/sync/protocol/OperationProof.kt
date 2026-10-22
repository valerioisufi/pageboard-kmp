package io.github.valerioisufi.drawview.sync.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor

/**
 * Who made an operation, proven: [signature], by the device of its replica (see
 * [DeviceIdentity] and [Membership.deviceOf]), of the document, the replica, the sequence and
 * [content] (the operation as it travels, before its encryption). The key of the document is not
 * in it, so that it stays good when the key changes; the devices give it on as they received it,
 * the content not encoded again, so that nothing on the way makes it differ.
 */
@Serializable
public class OperationProof(public val content: ByteArray, public val signature: ByteArray) {

    /** Whether the device [device] made the operation [replica] / [sequence] of [documentId] with this content. */
    public fun isBy(device: String, documentId: String, replica: String, sequence: Long): Boolean =
        DeviceIdentity.verify(device, signed(documentId, replica, sequence, content), signature)

    /** This proof as it is kept (see `OperationLog.saveProofs` of `drawview-store`). */
    public fun encode(): ByteArray = cbor.encodeToByteArray(serializer(), this)

    public companion object {
        @OptIn(ExperimentalSerializationApi::class)
        private val cbor = Cbor { ignoreUnknownKeys = true }

        /** The proof of the operation [replica] / [sequence] of [documentId], of [content], by [identity]. */
        public fun sign(identity: DeviceIdentity, documentId: String, replica: String, sequence: Long, content: ByteArray): OperationProof =
            OperationProof(content, identity.sign(signed(documentId, replica, sequence, content)))

        /** The proof kept as [bytes] (see [encode]), `null` when they are not one. */
        public fun decode(bytes: ByteArray): OperationProof? = try {
            cbor.decodeFromByteArray(serializer(), bytes)
        } catch (_: Exception) {
            null
        }

        /** What is signed: each part with its length first, so that no other parts give the same bytes. */
        private fun signed(documentId: String, replica: String, sequence: Long, content: ByteArray): ByteArray =
            "drawview-sync/operation|${documentId.length}:$documentId|${replica.length}:$replica|$sequence|".encodeToByteArray() + content
    }
}
