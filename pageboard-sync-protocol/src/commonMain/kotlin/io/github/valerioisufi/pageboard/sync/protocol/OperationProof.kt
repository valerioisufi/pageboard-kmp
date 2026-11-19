package io.github.valerioisufi.pageboard.sync.protocol

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

    /** Whether the device [device] made the change [replica] / [sequence] of the shared folder [spaceId] with this content. */
    public fun isCatalogBy(device: String, spaceId: String, replica: String, sequence: Long): Boolean =
        DeviceIdentity.verify(device, signed(CATALOG_LABEL, spaceId, replica, sequence, content), signature)

    /** This proof as it is kept (see `OperationLog.saveProofs` of `pageboard-store`). */
    public fun encode(): ByteArray = cbor.encodeToByteArray(serializer(), this)

    public companion object {
        @OptIn(ExperimentalSerializationApi::class)
        private val cbor = Cbor { ignoreUnknownKeys = true }

        /** The proof of the operation [replica] / [sequence] of [documentId], of [content], by [identity]. */
        public fun sign(identity: DeviceIdentity, documentId: String, replica: String, sequence: Long, content: ByteArray): OperationProof =
            OperationProof(content, identity.sign(signed(documentId, replica, sequence, content)))

        /** The proof of the change [replica] / [sequence] of the shared folder [spaceId] (see `docs/plans/shared-folders.md`), of [content], by [identity]. */
        public fun signCatalog(identity: DeviceIdentity, spaceId: String, replica: String, sequence: Long, content: ByteArray): OperationProof =
            OperationProof(content, identity.sign(signed(CATALOG_LABEL, spaceId, replica, sequence, content)))

        /** The proof kept as [bytes] (see [encode]), `null` when they are not one. */
        public fun decode(bytes: ByteArray): OperationProof? = try {
            cbor.decodeFromByteArray(serializer(), bytes)
        } catch (_: Exception) {
            null
        }

        /** What is signed: each part with its length first, so that no other parts give the same bytes. */
        private fun signed(documentId: String, replica: String, sequence: Long, content: ByteArray): ByteArray =
            signed(OPERATION_LABEL, documentId, replica, sequence, content)

        private fun signed(label: String, id: String, replica: String, sequence: Long, content: ByteArray): ByteArray =
            "$label|${id.length}:$id|${replica.length}:$replica|$sequence|".encodeToByteArray() + content

        private const val OPERATION_LABEL = "pageboard-sync/operation"

        /** The changes of a shared folder are signed apart from the operations of the documents: neither passes for the other. */
        private const val CATALOG_LABEL = "pageboard-sync/catalog"
    }
}
