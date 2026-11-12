package io.github.valerioisufi.pencilnotes

import io.github.valerioisufi.drawview.agent.AgentEndpoint
import java.io.IOException
import java.io.InputStream
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The lines of a client of the Model Context Protocol forwarded to the application and back, for as
 * long as the client goes on (Indirection): the application not running yet, quitting or starting
 * again does not end it, since a client takes a bridge that ended for a server that failed. See
 * `docs/agent-ergonomics.md`.
 *
 * One message at a time, as the application answers them: a request, then its answer. When the
 * application is not there, a request is answered here that it is not running; a client that opens
 * with `initialize` is told so in the instructions and kept, and its `initialize` is sent again to
 * the application once it is there, with a notice that the tools changed.
 *
 * @param connect the socket of the application, or `null` when nobody listens.
 * @param waitMs how long the application is waited for when it went away: it may be starting again.
 * @param firstWaitMs how long it is waited for before it was ever there.
 * @param watchMs how often it is looked for while a client waits for it.
 */
internal class McpBridge(
    private val connect: () -> SocketChannel?,
    input: InputStream,
    private val output: PrintStream,
    private val waitMs: Long = 10_000L,
    private val firstWaitMs: Long = minOf(waitMs, 2_000L),
    private val retryMs: Long = 100L,
    private val watchMs: Long = minOf(waitMs, 2_000L),
    private val maxAnswerBytes: Int = 64 * 1024 * 1024
) {
    private val requests = BoundedLineReader(input, AgentEndpoint.MAX_MESSAGE_LENGTH)

    /** Guards the socket and what is written to the client. */
    private val link = Any()
    private var channel: SocketChannel? = null
    private var answers: BoundedLineReader? = null
    private var wasConnected = false

    /** How the client opened, to open again with the application when it comes back. */
    private var initialize: String? = null
    private var initialized: String? = null

    /** The client was answered `initialize` here: it has still to be told that the tools are there. */
    private var waitsForTools = false

    @Volatile
    private var ended = false

    /** Forwards until the client ends its input. */
    fun run() {
        val watcher = thread(isDaemon = true, name = "agent-bridge-watch") { watch() }
        try {
            while (true) {
                val line = requests.readLine() ?: break
                if (line.isNotBlank()) forward(line)
            }
        } catch (_: IOException) {
            // The client went away, or sent a line too long
        } finally {
            ended = true
            watcher.interrupt()
            synchronized(link) { close() }
        }
    }

    private fun forward(line: String) {
        val message = try {
            Json.parseToJsonElement(line) as? JsonObject
        } catch (_: SerializationException) {
            null
        }
        val method = (message?.get("method") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        // As the application answers: whatever is not a notification
        val answered = message == null || "id" in message
        val id = message?.get("id") ?: JsonNull
        synchronized(link) {
            if (method == "initialize") initialize = line
            if (method == "notifications/initialized") initialized = line
            when (val sent = exchange(line, answered, opens = method == "initialize")) {
                is Exchange.Done -> sent.answer?.let(::emit)
                Exchange.Lost -> if (answered) emit(failure(id, "PencilNotes went away before it answered: what was asked may have been done. Read again before asking again."))
                Exchange.Absent -> when {
                    !answered -> {}
                    method == "initialize" && message != null -> {
                        waitsForTools = true
                        emit(openedHere(id, message))
                    }
                    method == "ping" -> emit(result(id, JsonObject(emptyMap())))
                    else -> emit(failure(id, NOT_RUNNING))
                }
            }
        }
    }

    private sealed interface Exchange {
        /** Sent, and answered when it is a request. */
        class Done(val answer: String?) : Exchange

        /** Sent, but the application went away before its answer. */
        data object Lost : Exchange

        /** The application is not there. */
        data object Absent : Exchange
    }

    /** Sends [line] to the application and reads its answer, reaching it again once when it went away meanwhile. */
    private fun exchange(line: String, answered: Boolean, opens: Boolean): Exchange {
        repeat(2) {
            val socket = channel ?: open(if (wasConnected) waitMs else firstWaitMs, replay = !opens) ?: return Exchange.Absent
            try {
                socket.writeLine(line)
            } catch (_: IOException) {
                // Nothing of it was taken: asked again of the application that came back
                close()
                return@repeat
            }
            if (!answered) return Exchange.Done(null)
            return try {
                Exchange.Done(answers!!.readLine() ?: throw IOException("closed"))
            } catch (_: IOException) {
                // It may have been done: not asked twice
                close()
                Exchange.Lost
            }
        }
        return Exchange.Absent
    }

    /** The socket of the application, waited for [waitMs]; [replay]: opened as the client opened. */
    private fun open(waitMs: Long, replay: Boolean): SocketChannel? {
        val until = System.currentTimeMillis() + waitMs
        while (true) {
            val socket = connect()
            if (socket != null) {
                channel = socket
                answers = BoundedLineReader(Channels.newInputStream(socket), maxAnswerBytes)
                try {
                    if (replay) {
                        initialize?.let {
                            socket.writeLine(it)
                            answers!!.readLine() ?: throw IOException("closed")
                        }
                        initialized?.let { socket.writeLine(it) }
                    }
                } catch (_: IOException) {
                    close()
                    return null
                }
                wasConnected = true
                if (waitsForTools && replay) {
                    waitsForTools = false
                    emit("""{"jsonrpc":"2.0","method":"notifications/tools/list_changed"}""")
                }
                return socket
            }
            if (ended || System.currentTimeMillis() >= until) return null
            try {
                Thread.sleep(retryMs)
            } catch (_: InterruptedException) {
                return null
            }
        }
    }

    /** Looks for the application while a client that was told it is not running waits for its tools. */
    private fun watch() {
        try {
            while (!ended) {
                Thread.sleep(watchMs)
                synchronized(link) { if (!ended && waitsForTools && channel == null) open(0L, replay = true) }
            }
        } catch (_: InterruptedException) {
            // The client ended
        }
    }

    private fun close() {
        try {
            channel?.close()
        } catch (_: IOException) {
        }
        channel = null
        answers = null
    }

    private fun emit(line: String) {
        output.println(line)
        output.flush()
    }

    /** The answer to an `initialize` nobody is there to answer: the client is kept, and told why it has no tools yet. */
    private fun openedHere(id: JsonElement, request: JsonObject): String {
        val requested = ((request["params"] as? JsonObject)?.get("protocolVersion") as? JsonPrimitive)?.contentOrNull
        return result(id, buildJsonObject {
            put("protocolVersion", AgentEndpoint.legacyVersionFor(requested))
            putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", true) } }
            putJsonObject("serverInfo") {
                put("name", "PencilNotes")
                put("version", "bridge")
            }
            put("instructions", NOT_RUNNING)
        })
    }

    private fun result(id: JsonElement, value: JsonObject): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", value)
    }.toString()

    private fun failure(id: JsonElement, message: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") {
            put("code", SERVER_ERROR)
            put("message", message)
        }
    }.toString()

    private fun SocketChannel.writeLine(line: String) {
        val bytes = ByteBuffer.wrap((line + "\n").toByteArray(StandardCharsets.UTF_8))
        while (bytes.hasRemaining()) write(bytes)
    }

    private companion object {
        /** An error of the server, in the range JSON-RPC leaves to it. */
        const val SERVER_ERROR = -32000
        const val NOT_RUNNING =
            "PencilNotes is not running on these notes, or the access of external models is off. The user opens it and turns it on in \"External models\", in the library or in the menu of a document; then ask again."
    }
}
