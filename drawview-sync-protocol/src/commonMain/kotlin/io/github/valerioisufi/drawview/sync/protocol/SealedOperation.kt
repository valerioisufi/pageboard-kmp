package io.github.valerioisufi.drawview.sync.protocol

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.operations.AuthenticatedCipher
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable

/**
 * An operation of a document as it travels: its identity in clear (the [replica] that made it and
 * its [sequence] number there), its content encrypted with the key of the document. Whoever
 * carries it without the key (a relay) can keep the operations of a document and give a device
 * the ones after its version vector, without reading them; the identity is authenticated with the
 * content, so that it cannot be changed on the way (see [OperationSeal]).
 */
@Serializable
public class SealedOperation(public val replica: String, public val sequence: Long, public val content: ByteArray)

/**
 * Seals and opens the operations of the document of [key] (AES-256-GCM, a random nonce per
 * operation; the version of the protocol, the tag of the document and the identity of the
 * operation authenticated with its content).
 */
public class OperationSeal(private val key: DocumentKey) {
    private val cipher: AuthenticatedCipher =
        CryptographyProvider.Default.get(AES.GCM).keyDecoder().decodeFromByteArrayBlocking(AES.Key.Format.RAW, key.toByteArray()).cipher()

    /** The operation [replica] / [sequence] of [content] (its encoded form), sealed. */
    public fun seal(replica: String, sequence: Long, content: ByteArray): SealedOperation =
        SealedOperation(replica, sequence, cipher.encryptBlocking(content, associatedData(replica, sequence)))

    /**
     * The content of [operation].
     *
     * @throws SyncException when it was not sealed with the key of the document, or was changed
     *   on the way (its identity included).
     */
    public fun open(operation: SealedOperation): ByteArray = try {
        cipher.decryptBlocking(operation.content, associatedData(operation.replica, operation.sequence))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw SyncException("An operation not sealed with the key of the document", e)
    }

    private fun associatedData(replica: String, sequence: Long): ByteArray =
        "drawview-sync/$PROTOCOL_VERSION/operation|${key.tag}|$replica|$sequence".encodeToByteArray()
}
