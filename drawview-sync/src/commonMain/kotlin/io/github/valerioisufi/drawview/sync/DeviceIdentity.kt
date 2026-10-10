package io.github.valerioisufi.drawview.sync

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.SHA256
import io.github.valerioisufi.drawview.platform.SecureStore
import io.github.valerioisufi.drawview.platform.Sha256
import kotlin.io.encoding.Base64

/**
 * Who this device is to the others (an ECDSA P-256 key pair): it proves it at every connection
 * ([sign]), so that the devices know who they work with, whatever key of a document it has (a
 * device removed from a document cannot pass for another one). [publicKey] names it in the
 * memberships of the documents (see [Membership]).
 */
class DeviceIdentity private constructor(private val privateKey: ECDSA.PrivateKey, val publicKey: String) {

    /** The signature of [bytes] by this device. */
    fun sign(bytes: ByteArray): ByteArray = privateKey.signatureGenerator(SHA256, ECDSA.SignatureFormat.RAW).generateSignatureBlocking(bytes)

    override fun toString(): String = "DeviceIdentity(${fingerprint(publicKey)})"

    companion object {
        private const val SECRET_NAME = "drawview-sync-identity"
        private val ecdsa get() = CryptographyProvider.Default.get(ECDSA)
        private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

        /** A new identity. */
        fun generate(): DeviceIdentity {
            val pair = ecdsa.keyPairGenerator(EC.Curve.P256).generateKeyBlocking()
            return DeviceIdentity(pair.privateKey, base64.encode(pair.publicKey.encodeToByteArrayBlocking(EC.PublicKey.Format.DER)))
        }

        /** The identity of this device, kept in the [SecureStore] (made the first time). */
        fun ofThisDevice(): DeviceIdentity {
            SecureStore.read(SECRET_NAME)?.let { stored -> decode(stored)?.let { return it } }
            val identity = generate()
            SecureStore.write(SECRET_NAME, identity.encode())
            return identity
        }

        /** Whether [signature] is the one of [bytes] by the device [publicKey]. */
        fun verify(publicKey: String, bytes: ByteArray, signature: ByteArray): Boolean = try {
            val key = ecdsa.publicKeyDecoder(EC.Curve.P256).decodeFromByteArrayBlocking(EC.PublicKey.Format.DER, base64.decode(publicKey))
            key.signatureVerifier(SHA256, ECDSA.SignatureFormat.RAW).tryVerifySignatureBlocking(bytes, signature)
        } catch (_: Exception) {
            false
        }

        /** A short name of the device [publicKey] (e.g. in the invitations, see [Invitation.owner]). */
        fun fingerprint(publicKey: String): String = Sha256.of(publicKey.encodeToByteArray()).take(16)

        private fun DeviceIdentity.encode(): String =
            base64.encode(privateKey.encodeToByteArrayBlocking(EC.PrivateKey.Format.DER)) + "." + publicKey

        private fun decode(stored: String): DeviceIdentity? = try {
            val (private, public) = stored.split('.')
            DeviceIdentity(ecdsa.privateKeyDecoder(EC.Curve.P256).decodeFromByteArrayBlocking(EC.PrivateKey.Format.DER, base64.decode(private)), public)
        } catch (_: Exception) {
            null
        }
    }
}
