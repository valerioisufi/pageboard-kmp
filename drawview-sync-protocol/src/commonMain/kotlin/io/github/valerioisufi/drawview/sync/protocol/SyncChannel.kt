package io.github.valerioisufi.drawview.sync.protocol

import kotlinx.coroutines.flow.Flow

/**
 * A connection with another device: whole frames, in order, both ways. The local network, a
 * relay or the memory of the tests are transports of them (see [SyncTransport]).
 */
public interface SyncChannel {
    /** Sends [frame] whole (the frames are kept apart). */
    public suspend fun send(frame: ByteArray)

    /** The next frame of the other device, or `null` when the connection is closed. */
    public suspend fun receive(): ByteArray?

    public fun close()
}

/**
 * A way to reach the other devices: [connect] opens a channel to a device, [incoming] has the
 * channels the other devices open to this one.
 */
public interface SyncTransport {
    public val incoming: Flow<SyncChannel>

    /** Opens a channel to the device at [address] (in the terms of this transport). */
    public suspend fun connect(address: String): SyncChannel

    public fun close()
}

/** A failure of the synchronization: a connection lost, refused, or a frame not understood. */
public class SyncException(message: String, cause: Throwable? = null) : Exception(message, cause)
