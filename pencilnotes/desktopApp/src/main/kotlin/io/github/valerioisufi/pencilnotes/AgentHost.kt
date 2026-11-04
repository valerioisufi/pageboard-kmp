package io.github.valerioisufi.pencilnotes

import io.github.valerioisufi.drawview.agent.AgentEndpoint
import io.github.valerioisufi.drawview.agent.AgentAccess
import java.io.File
import java.io.IOException
import java.net.StandardProtocolFamily
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.Channels
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/**
 * The way in of the clients on this machine: listens on the local socket of the data directory while
 * the user keeps the access on, and hands the lines of each client to the [endpoint]. Started by
 * the application, which holds its data directory: nobody else can be listening there.
 */
internal class AgentHost(
    private val dataDirectory: File,
    private val access: AgentAccess,
    private val endpoint: AgentEndpoint,
    private val maxClients: Int = 16
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = ConcurrentHashMap.newKeySet<SocketChannel>()
    private var server: ServerSocketChannel? = null
    private var serverJob: Job? = null

    /** Follows the setting of the user, until [stop]. */
    fun start() {
        scope.launch {
            access.load()
            access.grants.distinctUntilChangedBy { it.enabled }.collect { grants ->
                if (grants.enabled) listen() else closeAll()
            }
        }
    }

    fun stop() {
        closeAll()
        scope.cancel()
    }

    private fun listen() {
        if (server != null) return
        val socket = AgentSocket.fileOf(dataDirectory)
        val channel = try {
            AgentSocket.prepareDirectory(socket)
            // Left by a process that died: this process holds the directory now
            Files.deleteIfExists(socket.toPath())
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply { bind(AgentSocket.addressOf(socket)) }
        } catch (e: IOException) {
            println("PencilNotes: cannot listen for the external models: ${e.message}")
            return
        }
        server = channel
        serverJob = scope.launch { accept(channel) }
    }

    private suspend fun accept(server: ServerSocketChannel) {
        try {
            while (true) {
                val client = runInterruptible { server.accept() }
                if (clients.size >= maxClients) {
                    client.close()
                    continue
                }
                clients += client
                scope.launch {
                    try {
                        serve(client)
                    } finally {
                        clients -= client
                        client.close()
                    }
                }
            }
        } catch (_: ClosedChannelException) {
            // The access was turned off
        } catch (_: AsynchronousCloseException) {
            // The access was turned off
        }
    }

    private suspend fun serve(client: SocketChannel) {
        val connection = endpoint.connect()
        try {
            val lines = BoundedLineReader(Channels.newInputStream(client), AgentEndpoint.MAX_MESSAGE_LENGTH)
            while (true) {
                val line = runInterruptible { lines.readLine() } ?: return
                if (line.isBlank()) continue
                val answer = connection.handle(line) ?: continue
                val bytes = ByteBuffer.wrap((answer + "\n").toByteArray(StandardCharsets.UTF_8))
                runInterruptible { while (bytes.hasRemaining()) client.write(bytes) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            // The client went away, or sent a line too long: the connection ends
        } finally {
            connection.close()
        }
    }

    private fun closeAll() {
        val listening = server
        server = null
        serverJob = null
        try {
            listening?.close()
        } catch (_: IOException) {
        }
        for (client in clients) {
            try {
                client.close()
            } catch (_: IOException) {
            }
        }
        if (listening != null) {
            try {
                Files.deleteIfExists(AgentSocket.fileOf(dataDirectory).toPath())
            } catch (_: IOException) {
            }
        }
    }
}
