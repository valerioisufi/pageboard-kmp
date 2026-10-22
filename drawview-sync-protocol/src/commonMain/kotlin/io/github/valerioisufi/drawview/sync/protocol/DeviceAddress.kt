package io.github.valerioisufi.drawview.sync.protocol

import kotlinx.serialization.Serializable

/**
 * Where the device [device] ([DeviceIdentity.publicKey]) can be reached on the transport
 * [transport] (`iroh`...), as of [issuedAt] (milliseconds since the epoch): [address], in the
 * terms of that transport (see [SyncTransport.connect]).
 *
 * The device signs it ([signature]): whoever relays it cannot change it, nor pass another
 * device off as it. Of two addresses of the same device and transport, the more recent one is
 * kept. The members of a document give each other their addresses, so that each one can reach
 * the others when they are not on the same network.
 */
@Serializable
public class DeviceAddress(
    public val device: String,
    public val transport: String,
    public val address: String,
    public val issuedAt: Long,
    public val signature: ByteArray
) {
    /** Whether [device] signed it as it is. */
    public fun isSigned(): Boolean = DeviceIdentity.verify(device, signedBytes(device, transport, address, issuedAt), signature)

    /** Whether it is more recent than [other] (of the same device and transport). */
    public fun isNewerThan(other: DeviceAddress?): Boolean = other == null || issuedAt > other.issuedAt

    override fun equals(other: Any?): Boolean = other is DeviceAddress && device == other.device &&
        transport == other.transport && address == other.address && issuedAt == other.issuedAt &&
        signature.contentEquals(other.signature)

    override fun hashCode(): Int = (device.hashCode() * 31 + transport.hashCode()) * 31 + issuedAt.hashCode()

    override fun toString(): String = "DeviceAddress(${DeviceIdentity.fingerprint(device)}, $transport, $address, $issuedAt)"

    public companion object {
        /** The address [address] of this device ([identity]) on [transport], signed, as of [issuedAt]. */
        public fun sign(identity: DeviceIdentity, transport: String, address: String, issuedAt: Long): DeviceAddress =
            DeviceAddress(identity.publicKey, transport, address, issuedAt, identity.sign(signedBytes(identity.publicKey, transport, address, issuedAt)))

        private fun signedBytes(device: String, transport: String, address: String, issuedAt: Long): ByteArray =
            "drawview-sync/address|$device|$transport|$issuedAt|$address".encodeToByteArray()
    }
}
