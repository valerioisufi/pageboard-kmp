package io.github.valerioisufi.pencilnotes

import io.github.valerioisufi.drawview.agent.AgentEndpoint
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PrintStream
import java.net.StandardProtocolFamily
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The commands of PencilNotes in a terminal: each one a client of the application running on the same
 * data directory, never opening its database. `mcp` is the same for a client of the Model Context
 * Protocol, forwarding its messages. See `docs/agent-tools.md`.
 */
internal class AgentCli(
    private val dataDirectory: File,
    private val output: PrintStream = System.out,
    private val errors: PrintStream = System.err,
    private val input: InputStream = System.`in`,
    private val waitIntervalMs: Long = 1000L
) {
    /** Runs the command [args]; its exit code. */
    fun run(args: List<String>): Int {
        val command = args.firstOrNull()
        return try {
            when (command) {
                "docs" -> docs(args.drop(1))
                "read" -> read(args.drop(1))
                "search" -> search(args.drop(1))
                "preview" -> preview(args.drop(1))
                "propose" -> propose(args.drop(1))
                "status" -> status(args.drop(1))
                "mcp" -> mcp()
                else -> usage()
            }
        } catch (e: Failure) {
            errors.println(e.message)
            e.exitCode
        } catch (e: IOException) {
            errors.println("The connection with PencilNotes ended: ${e.message}")
            EXIT_NOT_RUNNING
        }
    }

    private fun usage(): Int {
        errors.println(
            """
            Usage:
              pencilnotes docs [--json]
              pencilnotes read <document> [--page <n>] [--json]
              pencilnotes search "<text>" [--json]
              pencilnotes preview <document> --file <items.json> [--json]
              pencilnotes propose <document> --file <items.json> [--title "<title>"]
              pencilnotes status <proposal> [--wait]
              pencilnotes mcp
            """.trimIndent()
        )
        return EXIT_USAGE
    }

    private fun docs(args: List<String>): Int = connected { client ->
        val answer = client.call("list_documents", JsonObject(emptyMap()))
        if ("--json" in args) {
            output.println(answer)
        } else {
            val documents = answer.array("documents")
            for (document in documents) {
                val fields = document.jsonObject
                output.println("${fields.text("id")}  ${fields.text("title")}  (${fields.text("pages")} pages)")
            }
            if (documents.isEmpty()) errors.println(answer.text("note"))
        }
        EXIT_OK
    }

    private fun read(args: List<String>): Int {
        val parsed = Arguments(args)
        val document = parsed.positional.firstOrNull() ?: return usage()
        return connected { client ->
            val page = parsed.option("--page")
            if (page == null) {
                val answer = client.call("read_document", buildJsonObject { put("document", document) })
                if (parsed.has("--json")) output.println(answer) else {
                    output.println(answer.text("title"))
                    for (entry in answer.array("pages")) {
                        val fields = entry.jsonObject
                        output.println("  page ${fields.text("index")}  ${fields.text("id")}  ${fields.text("texts")} texts, ${fields.text("strokes")} strokes, ${fields.text("images")} images, ${fields.text("pdfs")} PDF pages")
                    }
                }
            } else {
                val index = page.toIntOrNull() ?: throw Failure("--page takes the index of a page, from 0.", EXIT_USAGE)
                val answer = client.call("read_page", buildJsonObject {
                    put("document", document)
                    put("index", index)
                })
                if (parsed.has("--json")) output.println(answer) else printPage(answer)
            }
            EXIT_OK
        }
    }

    private fun printPage(page: JsonObject) {
        output.println("# Page ${page.text("index")}  ${page.text("id")}  (${page.text("strokes")} strokes)")
        for (entry in page.array("texts")) {
            val fields = entry.jsonObject
            output.println()
            output.println("## Text ${fields.text("id")}  base ${fields.text("base")}")
            output.println(fields.text("markdown"))
        }
        page.text("pdf_text")?.let {
            output.println()
            output.println("## PDF page")
            output.println(it)
        }
        page.text("handwriting")?.let {
            output.println()
            output.println("## Handwriting")
            output.println(it)
        }
    }

    private fun search(args: List<String>): Int {
        val parsed = Arguments(args)
        val query = parsed.positional.firstOrNull() ?: return usage()
        return connected { client ->
            val answer = client.call("search", buildJsonObject { put("query", query) })
            if (parsed.has("--json")) output.println(answer) else {
                for (passage in answer.array("passages")) {
                    val fields = passage.jsonObject
                    output.println("${fields.text("title")}  ${fields.text("document")}  ${fields.text("page").orEmpty()}")
                    output.println("  ${fields.text("snippet")}")
                }
            }
            EXIT_OK
        }
    }

    private fun preview(args: List<String>): Int {
        val parsed = Arguments(args)
        val document = parsed.positional.firstOrNull() ?: return usage()
        val path = parsed.option("--file") ?: return usage()
        val items = readItems(File(path), path)
        return connected { client ->
            val answer = client.call("preview_proposal", buildJsonObject {
                put("document", document)
                put("items", items)
            })
            if (parsed.has("--json")) output.println(answer) else printPlan(answer, output)
            EXIT_OK
        }
    }

    private fun propose(args: List<String>): Int {
        val parsed = Arguments(args)
        val document = parsed.positional.firstOrNull() ?: return usage()
        val path = parsed.option("--file") ?: return usage()
        val file = File(path)
        val items = readItems(file, path)
        return connected { client ->
            val answer = client.call("propose", buildJsonObject {
                put("document", document)
                put("title", parsed.option("--title") ?: file.nameWithoutExtension)
                put("items", items)
            })
            output.println(answer.text("proposal"))
            (answer["plan"] as? JsonObject)?.let { printWarnings(it, errors) }
            EXIT_OK
        }
    }

    /** The JSON array of items in [file]. */
    private fun readItems(file: File, path: String): JsonArray = try {
        if (file.length() > AgentEndpoint.MAX_MESSAGE_LENGTH) throw Failure("$path is too long.", EXIT_REFUSED)
        Json.parseToJsonElement(file.readText()) as? JsonArray
            ?: throw Failure("$path holds a JSON array of items.", EXIT_REFUSED)
    } catch (e: IOException) {
        throw Failure("Cannot read $path: ${e.message}", EXIT_USAGE)
    } catch (e: SerializationException) {
        throw Failure("$path is not JSON: ${e.message?.take(200)}", EXIT_REFUSED)
    }

    private fun printPlan(plan: JsonObject, to: PrintStream) {
        if (plan["measured"]?.jsonPrimitive?.booleanOrNull == false) to.println("The document is in no editor: the heights are estimates.")
        for ((index, entry) in plan.array("items").withIndex()) {
            val item = entry.jsonObject
            val stale = if (item["stale"]?.jsonPrimitive?.booleanOrNull == true) "  stale" else ""
            val size = item.text("font_size")?.let { "  size $it" }.orEmpty()
            to.println("Item $index: ${item.text("kind")}$size$stale")
            for (piece in item.array("pieces")) {
                val box = piece.jsonObject
                val page = if (box["new_page"]?.jsonPrimitive?.booleanOrNull == true) "new page" else "page"
                to.println("  $page ${box.text("page_index")}: x ${box.text("x")}, y ${box.text("y")}, ${box.text("width")} x ${box.text("height")} mm")
            }
            for (warning in item.array("warnings")) to.println("  warning: ${describe(warning.jsonObject)}")
        }
    }

    private fun printWarnings(plan: JsonObject, to: PrintStream) {
        for ((index, entry) in plan.array("items").withIndex()) {
            for (warning in entry.jsonObject.array("warnings")) to.println("Item $index: ${describe(warning.jsonObject)}")
        }
    }

    /** A warning in one line: its code and what it carries. */
    private fun describe(warning: JsonObject): String =
        warning.entries.joinToString(", ") { (name, value) ->
            if (name == "code") value.jsonPrimitive.content else "$name $value"
        }

    private fun status(args: List<String>): Int {
        val parsed = Arguments(args)
        val proposal = parsed.positional.firstOrNull() ?: return usage()
        return connected { client ->
            var answer = client.call("proposal_status", buildJsonObject { put("proposal", proposal) })
            while (parsed.has("--wait") && answer.text("state") == "waiting") {
                Thread.sleep(waitIntervalMs)
                answer = client.call("proposal_status", buildJsonObject { put("proposal", proposal) })
            }
            output.println(answer)
            when {
                answer.text("state") == "waiting" -> EXIT_OK
                answer.array("items").all { it.jsonPrimitive.contentOrNull == "accepted" } -> EXIT_OK
                else -> EXIT_NOT_ACCEPTED
            }
        }
    }

    /** Forwards the lines of a client of the protocol to the application and back, until one side ends. */
    private fun mcp(): Int {
        val channel = open()
        channel.use {
            val done = thread(isDaemon = true, name = "agent-answers") {
                try {
                    val lines = BoundedLineReader(Channels.newInputStream(channel), MAX_ANSWER_BYTES)
                    while (true) {
                        val line = lines.readLine() ?: break
                        output.println(line)
                        output.flush()
                    }
                } catch (_: IOException) {
                    // The application went away
                }
            }
            try {
                val lines = BoundedLineReader(input, AgentEndpoint.MAX_MESSAGE_LENGTH)
                while (done.isAlive) {
                    val line = lines.readLine() ?: break
                    channel.writeLine(line)
                }
                // Nothing more to ask: what was asked is still answered
                channel.shutdownOutput()
                done.join()
            } catch (_: IOException) {
                return EXIT_NOT_RUNNING
            }
        }
        return EXIT_OK
    }

    private fun <T> connected(block: (Client) -> T): T = open().use { block(Client(it)) }

    private fun open(): SocketChannel {
        val socket = AgentSocket.fileOf(dataDirectory)
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        try {
            channel.connect(AgentSocket.addressOf(socket))
        } catch (_: IOException) {
            channel.close()
            throw Failure(
                "PencilNotes is not running on these notes, or the access of external models is off. Open it, and turn it on in \"External models…\" in the menu of a document.",
                EXIT_NOT_RUNNING
            )
        }
        return channel
    }

    /** The requests of a command on one connection. */
    private class Client(private val channel: SocketChannel) {
        private val lines = BoundedLineReader(Channels.newInputStream(channel), MAX_ANSWER_BYTES)
        private var nextId = 1

        /** The value the tool [name] answers; fails with the exit code of its refusal. */
        fun call(name: String, arguments: JsonObject): JsonObject {
            val request = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", nextId++)
                put("method", "tools/call")
                putJsonObject("params") {
                    putJsonObject("_meta") {
                        put(AgentEndpoint.VERSION_KEY, AgentEndpoint.PROTOCOL_VERSION)
                        putJsonObject(AgentEndpoint.CLIENT_INFO_KEY) {
                            put("name", "Command line")
                            put("version", "1")
                        }
                    }
                    put("name", name)
                    put("arguments", arguments)
                }
            }
            channel.writeLine(request.toString())
            val line = lines.readLine() ?: throw Failure("PencilNotes closed the connection.", EXIT_NOT_RUNNING)
            val answer = Json.parseToJsonElement(line).jsonObject
            (answer["error"] as? JsonObject)?.let { throw Failure(it.text("message").orEmpty(), EXIT_REFUSED) }
            val result = answer.getValue("result").jsonObject
            val structured = result["structuredContent"]?.jsonObject ?: JsonObject(emptyMap())
            if ((result["isError"] as? JsonPrimitive)?.contentOrNull == "true") {
                val message = result["content"]?.jsonArray?.firstOrNull()?.jsonObject?.text("text").orEmpty()
                throw Failure(message, exitCodeOf(structured.text("code")))
            }
            return structured
        }
    }

    private class Arguments(args: List<String>) {
        val positional = mutableListOf<String>()
        private val options = mutableMapOf<String, String>()
        private val flags = mutableSetOf<String>()

        init {
            var index = 0
            while (index < args.size) {
                val arg = args[index++]
                when {
                    arg in VALUE_OPTIONS -> options[arg] = args.getOrNull(index++) ?: throw Failure("$arg needs a value.", EXIT_USAGE)
                    arg.startsWith("--") -> flags += arg
                    else -> positional += arg
                }
            }
        }

        fun option(name: String): String? = options[name]

        fun has(flag: String): Boolean = flag in flags
    }

    private class Failure(message: String, val exitCode: Int) : Exception(message)

    companion object {
        const val EXIT_OK = 0
        const val EXIT_USAGE = 1

        /** The application is not running, or the access is off. */
        const val EXIT_NOT_RUNNING = 2

        /** The document is not allowed. */
        const val EXIT_NOT_GRANTED = 3

        /** Refused by the checks. */
        const val EXIT_REFUSED = 4

        /** Items stale or rejected. */
        const val EXIT_NOT_ACCEPTED = 5

        /** The commands: what else is given to the application is not for this class. */
        val COMMANDS = setOf("docs", "read", "search", "propose", "status", "mcp", "help", "--help")

        /** The longest answer read back: a page drawn as an image, in Base64, fits. */
        private const val MAX_ANSWER_BYTES = 64 * 1024 * 1024
        private val VALUE_OPTIONS = setOf("--page", "--file", "--title")

        private fun exitCodeOf(code: String?): Int = when (code) {
            "access_off" -> EXIT_NOT_RUNNING
            "not_granted" -> EXIT_NOT_GRANTED
            else -> EXIT_REFUSED
        }

        private fun SocketChannel.writeLine(line: String) {
            val bytes = ByteBuffer.wrap((line + "\n").toByteArray(StandardCharsets.UTF_8))
            while (bytes.hasRemaining()) write(bytes)
        }

        private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.array(name: String): List<JsonElement> = (get(name) as? JsonArray).orEmpty()
    }
}
