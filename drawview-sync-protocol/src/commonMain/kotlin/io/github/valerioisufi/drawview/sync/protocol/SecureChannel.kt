package io.github.valerioisufi.drawview.sync.protocol

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.operations.AuthenticatedCipher
import kotlin.coroutines.cancellation.CancellationException

/**
 * [channel] with its frames encrypted with the key of a document (AES-256-GCM, a random nonce per
 * frame): a frame changed on the way, or encrypted with another key, is refused.
 */
public class SecureChannel private constructor(
    private val channel: SyncChannel,
    private val cipher: AuthenticatedCipher
) : SyncChannel {

    // The blocking forms: quick for frames, and they stay on the coroutine (no thread switch)
    override suspend fun send(frame: ByteArray): Unit = channel.send(cipher.encryptBlocking(frame, ASSOCIATED_DATA))

    override suspend fun receive(): ByteArray? {
        val frame = channel.receive() ?: return null
        return try {
            cipher.decryptBlocking(frame, ASSOCIATED_DATA)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SyncException("A frame not encrypted with the key of the document", e)
        }
    }

    override fun close(): Unit = channel.close()

    public companion object {
        /** The version of the protocol is authenticated with every frame. */
        private val ASSOCIATED_DATA = "drawview-sync/$PROTOCOL_VERSION".encodeToByteArray()

        /** [channel] with its frames encrypted with [key]. */
        public fun of(channel: SyncChannel, key: DocumentKey): SecureChannel {
            val aes = CryptographyProvider.Default.get(AES.GCM).keyDecoder().decodeFromByteArrayBlocking(AES.Key.Format.RAW, key.toByteArray())
            return SecureChannel(channel, aes.cipher())
        }
    }
}
