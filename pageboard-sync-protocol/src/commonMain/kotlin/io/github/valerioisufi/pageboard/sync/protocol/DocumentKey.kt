package io.github.valerioisufi.pageboard.sync.protocol

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.random.CryptographyRandom
import io.github.valerioisufi.pageboard.platform.Sha256
import kotlin.io.encoding.Base64

/**
 * The secret of a shared document: whoever has it can read and change the document. It encrypts
 * everything the devices exchange about the document (see [SecureChannel]) and travels in the
 * invitations (see [Invitation]).
 */
public class DocumentKey private constructor(private val bytes: ByteArray) {

    /** The key (32 bytes). */
    public fun toByteArray(): ByteArray = bytes.copyOf()

    /** The key as text (URL-safe base 64). */
    public fun encode(): String = base64.encode(bytes)

    /** The SHA-256 of the key, in hexadecimal: what the membership names it by ([Membership.keyHash]). */
    public val hash: String by lazy { Sha256.of(bytes) }

    /**
     * The tag of the document on the network: it tells a device which of its documents a
     * connection is about, without revealing the key.
     */
    public val tag: String by lazy {
        val key = CryptographyProvider.Default.get(HMAC).keyDecoder(SHA256).decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, bytes)
        key.signatureGenerator().generateSignatureBlocking(TAG_LABEL).toHex().take(32)
    }

    /**
     * The key of the document [documentId] of the shared folder this is the key of (see
     * `docs/plans/shared-folders.md`): an HMAC-SHA256 of the identifier, so that each document of the
     * folder has a key and a tag of its own, and whoever has the key of the folder has them all.
     */
    public fun forDocument(documentId: String): DocumentKey {
        val key = CryptographyProvider.Default.get(HMAC).keyDecoder(SHA256).decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, bytes)
        val label = "pageboard-sync/space-document|${documentId.length}:$documentId".encodeToByteArray()
        return DocumentKey(key.signatureGenerator().generateSignatureBlocking(label))
    }

    override fun equals(other: Any?): Boolean = other is DocumentKey && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "DocumentKey(…)"

    public companion object {
        internal const val SIZE = 32

        private val TAG_LABEL = "pageboard-sync/document-tag".encodeToByteArray()
        private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

        /** A new random key. */
        public fun generate(): DocumentKey = DocumentKey(CryptographyRandom.nextBytes(SIZE))

        /** The key of [bytes], or `null` when they are not a key. */
        public fun of(bytes: ByteArray): DocumentKey? = bytes.takeIf { it.size == SIZE }?.let { DocumentKey(it.copyOf()) }

        /** The key written by [encode], or `null` when [text] is not a key. */
        public fun decode(text: String): DocumentKey? = try {
            of(base64.decode(text))
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
