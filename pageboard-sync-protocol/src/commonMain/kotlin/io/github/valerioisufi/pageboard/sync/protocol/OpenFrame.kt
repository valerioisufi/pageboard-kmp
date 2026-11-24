package io.github.valerioisufi.pageboard.sync.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor

/**
 * The version of the protocol of the synchronization. 4: a document shared on its own and through
 * shared folders at once, the memberships of its other accesses given to check their members
 * (`AccessMembership`), the grants of the owners (`Membership.grants`) and the folders named
 * (`Membership.spaces`, `ShareItem`; docs/plans/shared-access.md). Version 3 had the owner of a
 * shared folder confirm who gives each document of it (`ConfirmGiver`, docs/plans/brought-by.md). Version 2 sent the files and
 * the sealed operations as byte strings (docs/plans/ios-stability.md). Version 1 had the addresses of
 * the devices ([DeviceAddress]) and the signed operations ([OperationProof]), its format recorded
 * again while the applications were on the devices of their author only.
 */
public const val PROTOCOL_VERSION: Int = 4

/**
 * The first frame of a connection, in clear: the version of the protocol and the document the
 * connection is about (see [DocumentKey.tag]), what a relay routes the connection by. Everything
 * else is encrypted with the key of the document (see [SecureChannel], [SealedOperation]).
 */
@Serializable
public data class OpenFrame(val protocol: Int, val tag: String) {
    /** The frame as it is sent (CBOR). */
    public fun encode(): ByteArray = cbor.encodeToByteArray(serializer(), this)

    public companion object {
        /** The largest open frame a device takes (the first frame of a device not known yet). */
        public const val MAX_SIZE: Int = 1024

        @OptIn(ExperimentalSerializationApi::class)
        private val cbor = Cbor { ignoreUnknownKeys = true }

        /** The frame of [bytes], or `null` when they are not one (anything a device sends: never a failure). */
        public fun decode(bytes: ByteArray): OpenFrame? = try {
            cbor.decodeFromByteArray(serializer(), bytes)
        } catch (_: Exception) {
            null
        }
    }
}
