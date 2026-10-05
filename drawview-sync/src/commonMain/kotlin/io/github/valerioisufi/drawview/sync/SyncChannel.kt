package io.github.valerioisufi.drawview.sync

import kotlinx.coroutines.flow.Flow

/**
 * A connection with another device: whole frames, in order, both ways. The local network, a
 * relay or the memory of the tests are transports of them (see [SyncTransport]).
 */
interface SyncChannel {
    /** Sends [frame] whole (the frames are kept apart). */
    suspend fun send(frame: ByteArray)

    /** The next frame of the other device, or `null` when the connection is closed. */
    suspend fun receive(): ByteArray?

    fun close()
}

/**
 * A way to reach the other devices: [connect] opens a channel to a device, [incoming] has the
 * channels the other devices open to this one.
 */
interface SyncTransport {
    val incoming: Flow<SyncChannel>

    /** Opens a channel to the device at [address] (in the terms of this transport). */
    suspend fun connect(address: String): SyncChannel

    fun close()
}

/** A failure of the synchronization: a connection lost, refused, or a frame not understood. */
class SyncException(message: String, cause: Throwable? = null) : Exception(message, cause)
