package io.github.valerioisufi.drawview.sync.iroh

import io.github.valerioisufi.drawview.sync.protocol.PROTOCOL_VERSION
import io.github.valerioisufi.drawview.sync.protocol.SyncChannel
import io.github.valerioisufi.drawview.sync.protocol.SyncException
import io.github.valerioisufi.drawview.sync.protocol.SyncTransport
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reaches the other devices over the internet with iroh (GRASP Polymorphism of the transports of
 * the synchronization): by their address (their identifier, their relays, the direct addresses
 * they know: see [address]), directly when the networks allow it, through the [relays]
 * otherwise. The connections are authenticated and encrypted by iroh with the key of each
 * device ([secret]); the synchronization still proves who each device is, and encrypts with the
 * key of each document, inside them.
 *
 * One connection per device, one stream per channel: the channels to the same device share its
 * connection, closed with the last of them.
 *
 * Without the iroh bridge on this platform ([isAvailable]), [start] fails.
 */
public class IrohTransport(
    private val scope: CoroutineScope,
    private val secret: IrohSecret,
    private val relays: RelayConfig,
    private val connectTimeoutMs: Long = 15_000
) : SyncTransport {
    private val accepted = Channel<SyncChannel>(Channel.UNLIMITED)
    private val lock = SynchronizedObject()
    private val connections = mutableMapOf<String, Opened>()
    private var endpoint = 0L
    private var listening: Job? = null

    private fun endpoint(): Long = synchronized(lock) { endpoint }

    /**
     * A connection to a device, being opened or open, with the channels on it: its number, or
     * what went wrong (the error read on the thread of the call, where the bridge keeps it).
     */
    private class Opened(val result: Deferred<Pair<Long, String>>) {
        var channels = 0
        val number: Long? get() = if (result.isCompleted && !result.isCancelled) @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) result.getCompleted().first else null
    }

    override val incoming: Flow<SyncChannel> = accepted.receiveAsFlow()

    /** The identifier of this device to the others (its public key), once [start]ed. */
    public var id: String? = null
        private set

    /** The address the others reach this device at, as it was when [start] gave it. */
    public var address: String? = null
        private set

    /**
     * Binds the endpoint, starts accepting the connections and gives its [address], once it
     * reached its home relay (at most [timeoutMs]).
     *
     * @throws SyncException without the iroh bridge, or when the endpoint cannot be bound.
     */
    public suspend fun start(timeoutMs: Long = 10_000): String {
        val bridge = bridge()
        address?.takeIf { endpoint() > 0 }?.let { return it }
        val number = withContext(IrohDispatcher) {
            bridge.bind(secret.bytes, relays.bridgeRelays, relays.token.orEmpty(), ALPN).also {
                if (it <= 0) throw SyncException("iroh: ${bridge.lastError()}")
            }
        }
        synchronized(lock) { endpoint = number }
        id = bridge.id(number)
        listening = scope.launch(IrohDispatcher) { acceptConnections(bridge, number) }
        val address = withContext(IrohDispatcher) { bridge.address(number, timeoutMs) }
            ?: throw SyncException("iroh: the endpoint is closed")
        this.address = address
        return address
    }

    /** The address of this device now (its relays and direct addresses change with the network). */
    public suspend fun currentAddress(timeoutMs: Long = 0): String? {
        val endpoint = endpoint().takeIf { it > 0 } ?: return null
        return withContext(IrohDispatcher) { bridge().address(endpoint, timeoutMs) }?.also { address = it }
    }

    /** Tells the endpoint that the network changed (after the background, another Wi-Fi...). */
    public suspend fun networkChanged() {
        val endpoint = endpoint().takeIf { it > 0 } ?: return
        withContext(IrohDispatcher) { bridge().networkChange(endpoint) }
    }

    /**
     * Opens a channel to the device at [address]: a stream on its connection, opened first when
     * there is none.
     */
    override suspend fun connect(address: String): SyncChannel {
        val bridge = bridge()
        val id = address.substringBefore(' ')
        // A connection found closed (the other device went away) is opened again, once
        repeat(2) {
            val opened = synchronized(lock) {
                val endpoint = endpoint.takeIf { it > 0 } ?: throw SyncException("The transport is not started")
                connections.getOrPut(id) {
                    Opened(
                        scope.async(IrohDispatcher) {
                            val number = bridge.connect(endpoint, address, connectTimeoutMs)
                            number to if (number == IrohBridge.ERROR) bridge.lastError() else ""
                        }
                    )
                }.also { it.channels++ }
            }
            val (connection, connectError) = opened.result.await()
            val (stream, streamError) = if (connection <= 0) connection to connectError else withContext(IrohDispatcher) {
                val number = bridge.openStream(connection)
                number to if (number == IrohBridge.ERROR) bridge.lastError() else ""
            }
            if (stream > 0) return IrohChannel(bridge, stream) { release(id, opened) }
            val error = streamError
            forget(id, opened)
            if (connection <= 0) {
                throw SyncException(if (connection == IrohBridge.TIMEOUT) "No answer from $id" else "Cannot connect to $id: $error")
            }
        }
        throw SyncException("Cannot open a channel to $id")
    }

    /** A channel on the connection of [opened] closed: the connection too with the last one. */
    private fun release(id: String, opened: Opened) {
        val close = synchronized(lock) {
            opened.channels--
            (opened.channels == 0 && connections[id] === opened).also { if (it) connections.remove(id) }
        }
        if (close) closeConnection(opened)
    }

    /** The connection of [opened] is not to be used again. */
    private fun forget(id: String, opened: Opened) {
        synchronized(lock) { if (connections[id] === opened) connections.remove(id) }
        closeConnection(opened)
    }

    /** Closes the connection of [opened] (open: its channels are all closed or failed). */
    private fun closeConnection(opened: Opened) {
        val connection = opened.number ?: return
        if (connection > 0) irohBridge?.closeConnection(connection)
    }

    private fun CoroutineScope.acceptConnections(bridge: IrohBridge, endpoint: Long) {
        while (isActive) {
            val connection = bridge.accept(endpoint, SLICE_MS)
            when {
                connection > 0 -> launch(IrohDispatcher) { acceptStreams(bridge, connection) }
                connection == IrohBridge.TIMEOUT -> continue
                // Closed
                else -> return
            }
        }
    }

    private fun CoroutineScope.acceptStreams(bridge: IrohBridge, connection: Long) {
        try {
            while (isActive) {
                val stream = bridge.acceptStream(connection, SLICE_MS)
                when {
                    stream > 0 -> {
                        val channel = IrohChannel(bridge, stream)
                        // Closed meanwhile: the stream with it
                        if (accepted.trySend(channel).isFailure) channel.close()
                    }
                    stream == IrohBridge.TIMEOUT -> continue
                    // The other device closed the connection, or went away
                    else -> return
                }
            }
        } finally {
            bridge.closeConnection(connection)
        }
    }

    /** Closes the endpoint, its connections and their channels. */
    override fun close() {
        listening?.cancel()
        accepted.close()
        val endpoint = synchronized(lock) {
            connections.clear()
            endpoint.also { endpoint = 0 }
        }
        if (endpoint > 0) irohBridge?.closeEndpoint(endpoint)
    }

    private fun bridge(): IrohBridge = irohBridge ?: throw SyncException("No iroh bridge on this platform")

    public companion object {
        /** The name of this transport in the addresses of the devices and of the invitations. */
        public const val NAME: String = "iroh"

        /** The protocol the endpoints answer (the version of the synchronization with it). */
        internal val ALPN: String = "drawview/sync/$PROTOCOL_VERSION"

        /** Whether the iroh bridge is on this platform (its native library is). */
        public val isAvailable: Boolean get() = irohBridge != null
    }
}
