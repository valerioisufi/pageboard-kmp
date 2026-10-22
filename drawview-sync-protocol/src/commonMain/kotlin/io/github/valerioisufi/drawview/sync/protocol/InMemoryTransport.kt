package io.github.valerioisufi.drawview.sync.protocol

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Devices connected in memory (tests, previews): each [transport] is reachable at its address.
 */
public class InMemoryNetwork {
    private val lock = SynchronizedObject()
    private val transports = mutableMapOf<String, InMemoryTransport>()

    /** The transport of the device at [address]. */
    public fun transport(address: String): InMemoryTransport = synchronized(lock) {
        transports.getOrPut(address) { InMemoryTransport(address, this) }
    }

    internal fun find(address: String): InMemoryTransport? = synchronized(lock) { transports[address] }

    internal fun remove(transport: InMemoryTransport): InMemoryTransport? = synchronized(lock) { transports.remove(transport.address) }
}

/** A device of an [InMemoryNetwork]. */
public class InMemoryTransport internal constructor(public val address: String, private val network: InMemoryNetwork) : SyncTransport {
    private val accepted = Channel<SyncChannel>(Channel.UNLIMITED)

    override val incoming: Flow<SyncChannel> = accepted.receiveAsFlow()

    override suspend fun connect(address: String): SyncChannel {
        val target = network.find(address) ?: throw SyncException("No device at $address")
        val (local, remote) = InMemoryChannel.pair()
        if (target.accepted.trySend(remote).isFailure) throw SyncException("The device at $address is gone")
        return local
    }

    override fun close() {
        network.remove(this)
        accepted.close()
    }
}

/** One end of a connection in memory. */
public class InMemoryChannel private constructor(
    private val inbox: Channel<ByteArray>,
    private val outbox: Channel<ByteArray>
) : SyncChannel {

    override suspend fun send(frame: ByteArray) {
        try {
            outbox.send(frame.copyOf())
        } catch (e: ClosedSendChannelException) {
            throw SyncException("The connection is closed", e)
        }
    }

    override suspend fun receive(maxSize: Int): ByteArray? {
        val frame = inbox.receiveCatching().getOrNull() ?: return null
        if (frame.size > maxSize) {
            close()
            throw SyncException("A frame of ${frame.size} bytes")
        }
        return frame
    }

    override fun close() {
        outbox.close()
        inbox.close()
    }

    public companion object {
        /** The two ends of a new connection. */
        public fun pair(): Pair<InMemoryChannel, InMemoryChannel> {
            val one = Channel<ByteArray>(Channel.UNLIMITED)
            val other = Channel<ByteArray>(Channel.UNLIMITED)
            return InMemoryChannel(one, other) to InMemoryChannel(other, one)
        }
    }
}
