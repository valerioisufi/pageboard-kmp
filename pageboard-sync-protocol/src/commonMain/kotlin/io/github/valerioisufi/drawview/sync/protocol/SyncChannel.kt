package io.github.valerioisufi.drawview.sync.protocol

import kotlinx.coroutines.flow.Flow

/**
 * A connection with another device: whole frames, in order, both ways. The local network, a
 * relay or the memory of the tests are transports of them (see [SyncTransport]).
 */
public interface SyncChannel {
    /** Sends [frame] whole (the frames are kept apart). */
    public suspend fun send(frame: ByteArray)

    /**
     * The next frame of the other device, or `null` when the connection is closed. A frame larger
     * than [maxSize] ends the channel before its bytes are read (a [SyncException], or `null`):
     * a device not known yet only sends small ones. The memory of a frame follows its bytes as
     * they come, not the length it announces.
     */
    public suspend fun receive(maxSize: Int = MAX_FRAME_SIZE): ByteArray?

    /** What the frames go through now (it may change: a relay, then a direct path). */
    public val route: Route get() = Route.UNKNOWN

    public fun close()
}

/** The largest frame of a [SyncChannel] (a snapshot of a document: its files go in parts). */
public const val MAX_FRAME_SIZE: Int = 256 * 1024 * 1024

/** What the frames of a [SyncChannel] go through. */
public enum class Route {
    /** The local network. */
    LOCAL_NETWORK,

    /** The internet, directly between the devices. */
    INTERNET_DIRECT,

    /** The internet, through a relay (which cannot read them). */
    INTERNET_RELAYED,

    /** Not known (the memory of the tests, a path being found). */
    UNKNOWN
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
