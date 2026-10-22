package io.github.valerioisufi.drawview.sync.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.cbor.Cbor

/**
 * The version of the protocol of the synchronization: 2 since the devices give each other their
 * addresses ([DeviceAddress]).
 */
public const val PROTOCOL_VERSION: Int = 2

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

        /** The frame of [bytes], or `null` when they are not one. */
        public fun decode(bytes: ByteArray): OpenFrame? = try {
            cbor.decodeFromByteArray(serializer(), bytes)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
