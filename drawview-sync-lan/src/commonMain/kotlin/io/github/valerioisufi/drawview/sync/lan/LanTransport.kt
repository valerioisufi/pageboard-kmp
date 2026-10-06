package io.github.valerioisufi.drawview.sync.lan

import io.github.valerioisufi.drawview.sync.SyncChannel
import io.github.valerioisufi.drawview.sync.SyncException
import io.github.valerioisufi.drawview.sync.SyncTransport
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.network.sockets.port
import io.ktor.utils.io.readFully
import io.ktor.utils.io.readInt
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * [SyncTransport] over TCP on the local network: [start] listens on [port] (0: a free port), the
 * addresses are `host:port`. A frame is its length (4 bytes, big-endian) and its bytes.
 */
class LanTransport(private val scope: CoroutineScope, private val port: Int = 0) : SyncTransport {
    private val selector = SelectorManager(Dispatchers.IO)
    private val accepted = Channel<SyncChannel>(Channel.UNLIMITED)
    private var server: ServerSocket? = null
    private var lastPort = port

    override val incoming: Flow<SyncChannel> = accepted.receiveAsFlow()

    /** The port this device listens on (0 until [start]). */
    val boundPort: Int get() = server?.port ?: 0

    /**
     * Whether this device listens for connections: no more once the system closed the listener
     * (e.g. an application suspended in the background), then [start] listens again.
     */
    val isListening: Boolean get() = server != null

    /**
     * Listens for the connections of the other devices (on the port of the last time when it is
     * free); returns the port.
     */
    suspend fun start(): Int {
        server?.let { return it.port }
        val socket = bind(lastPort) ?: bind(port) ?: bind(0) ?: throw SyncException("Cannot listen for the other devices")
        server = socket
        lastPort = socket.port
        scope.launch {
            try {
                while (true) accepted.send(TcpChannel(socket.accept()))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Closed (by this device or by the system)
            } finally {
                if (server === socket) server = null
                socket.close()
            }
        }
        return socket.port
    }

    /** Stops listening (the connections made stay). */
    fun stopListening() {
        server?.close()
        server = null
    }

    private suspend fun bind(port: Int): ServerSocket? = try {
        aSocket(selector).tcp().bind("0.0.0.0", port)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    override suspend fun connect(address: String): SyncChannel {
        val host = address.substringBeforeLast(':').removeSurrounding("[", "]")
        val port = address.substringAfterLast(':').toIntOrNull() ?: throw SyncException("Not an address: $address")
        return try {
            TcpChannel(aSocket(selector).tcp().connect(host, port))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SyncException("Cannot reach $address", e)
        }
    }

    override fun close() {
        stopListening()
        accepted.close()
        selector.close()
    }
}

/** A TCP connection carrying frames (see [LanTransport]). */
internal class TcpChannel(private val socket: Socket) : SyncChannel {
    private val input = socket.openReadChannel()
    private val output = socket.openWriteChannel(autoFlush = false)
    private val writing = Mutex()

    override suspend fun send(frame: ByteArray) = writing.withLock {
        try {
            output.writeInt(frame.size)
            output.writeFully(frame)
            output.flush()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SyncException("The connection is closed", e)
        }
    }

    override suspend fun receive(): ByteArray? {
        val size = try {
            input.readInt()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        if (size !in 0..MAX_FRAME_SIZE) throw SyncException("A frame of $size bytes")
        val frame = ByteArray(size)
        try {
            input.readFully(frame)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        return frame
    }

    override fun close() = socket.close()

    private companion object {
        const val MAX_FRAME_SIZE = 256 * 1024 * 1024
    }
}
