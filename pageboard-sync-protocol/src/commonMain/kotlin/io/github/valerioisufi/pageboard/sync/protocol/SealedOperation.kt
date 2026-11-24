package io.github.valerioisufi.pageboard.sync.protocol

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.operations.AuthenticatedCipher
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString

/**
 * An operation of a document as it travels: its identity in clear (the [replica] that made it and
 * its [sequence] number there), its content encrypted with the key of the document, and the
 * [signature] of its content by the device of the replica (see [OperationProof]). Whoever
 * carries it without the key (a relay) can keep the operations of a document and give a device
 * the ones after its version vector, without reading them; the identity is authenticated with the
 * content, so that it cannot be changed on the way (see [OperationSeal]), and a member cannot make
 * one pass for another member's.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
public class SealedOperation(
    public val replica: String,
    public val sequence: Long,
    // Byte strings since protocol 2 (arrays of numbers before, decoded one at a time)
    @ByteString public val content: ByteArray,
    @ByteString public val signature: ByteArray
)

/**
 * Seals and opens the operations of the document of [key] (AES-256-GCM, a random nonce per
 * operation; the version of the protocol, the tag of the document and the identity of the
 * operation authenticated with its content).
 */
public class OperationSeal(private val key: DocumentKey) {
    private val cipher: AuthenticatedCipher =
        CryptographyProvider.Default.get(AES.GCM).keyDecoder().decodeFromByteArrayBlocking(AES.Key.Format.RAW, key.toByteArray()).cipher()

    /** The operation [replica] / [sequence] of [proof] (its encoded form, signed), sealed. */
    public fun seal(replica: String, sequence: Long, proof: OperationProof): SealedOperation =
        SealedOperation(replica, sequence, cipher.encryptBlocking(proof.content, associatedData(replica, sequence)), proof.signature)

    /**
     * The content of [operation], with the signature of its author (checked by whoever knows the
     * device of its replica, see [OperationProof.isBy]).
     *
     * @throws SyncException when it was not sealed with the key of the document, or was changed
     *   on the way (its identity included).
     */
    public fun open(operation: SealedOperation): OperationProof = try {
        OperationProof(cipher.decryptBlocking(operation.content, associatedData(operation.replica, operation.sequence)), operation.signature)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw SyncException("An operation not sealed with the key of the document", e)
    }

    private fun associatedData(replica: String, sequence: Long): ByteArray =
        "pageboard-sync/$PROTOCOL_VERSION/operation|${key.tag}|$replica|$sequence".encodeToByteArray()
}
