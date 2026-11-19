package io.github.valerioisufi.drawview.sync.iroh

import dev.whyoleg.cryptography.random.CryptographyRandom
import io.github.valerioisufi.drawview.platform.SecureStore
import kotlin.io.encoding.Base64

/**
 * The key of the connections of this device (an Ed25519 secret key of 32 bytes): its public key
 * is the identifier the other devices reach it by. It is apart from the `DeviceIdentity`, which
 * every device checks inside the connection (see `SyncEngine`): the key of the connections
 * proves nothing about who the device is.
 */
public class IrohSecret private constructor(internal val bytes: ByteArray) {

    override fun toString(): String = "IrohSecret"

    public companion object {
        private const val SECRET_NAME = "pageboard-sync-iroh-key"
        private const val SIZE = 32

        /** A new key. */
        public fun generate(): IrohSecret = IrohSecret(CryptographyRandom.nextBytes(SIZE))

        /** The key of this device, kept in the [SecureStore] (made the first time). */
        public fun ofThisDevice(): IrohSecret {
            SecureStore.read(SECRET_NAME)
                ?.let { stored -> runCatching { Base64.decode(stored) }.getOrNull() }
                ?.takeIf { it.size == SIZE }
                ?.let { return IrohSecret(it) }
            return generate().also { SecureStore.write(SECRET_NAME, Base64.encode(it.bytes)) }
        }
    }
}
