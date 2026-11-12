package io.github.valerioisufi.pencilnotes

import io.github.valerioisufi.drawview.agent.AgentEndpoint
import io.github.valerioisufi.drawview.agent.AgentEngines
import io.github.valerioisufi.drawview.agent.ExternalAgents
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.store.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.store.KeyValueDocumentStore
import io.github.valerioisufi.drawview.store.OpenDocuments
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintStream
import java.net.StandardProtocolFamily
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** The commands against a real socket, on a data directory of the tests and an in-memory store. */
class AgentCliTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val directory: File = Files.createTempDirectory(File(System.getProperty("java.io.tmpdir")).toPath(), "cli").toFile()
    private val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = scope)
    private val documents = OpenDocuments(store, Dispatchers.Default, scope)
    private val agents = ExternalAgents(documents, AgentEngines(), store)
    private val host = AgentHost(directory, agents.access, AgentEndpoint(agents, "PencilNotes", "test"))

    private class Result(val code: Int, val output: String, val errors: String)

    private fun run(vararg args: String, input: ByteArray = ByteArray(0)): Result {
        val output = ByteArrayOutputStream()
        val errors = ByteArrayOutputStream()
        val code = AgentCli(directory, PrintStream(output), PrintStream(errors), ByteArrayInputStream(input), waitIntervalMs = 20).run(args.toList())
        return Result(code, output.toString(), errors.toString())
    }

    private fun startHost() {
        host.start()
        runBlocking { agents.access.setEnabled(true) }
        waitFor { AgentSocket.fileOf(directory).exists() }
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "It did not happen in time" }
            Thread.sleep(10)
        }
    }

    private fun newDocument(): String = runBlocking {
        val id = store.createDocument("Physics", 210f, 297f, PageBackground.Solid()).id
        agents.access.grant(id)
        id
    }

    @AfterTest
    fun finish() {
        host.stop()
        scope.cancel()
        directory.deleteRecursively()
    }

    @Test
    fun theApplicationNotRunningIsToldByItsExitCode() {
        val result = run("docs")
        assertEquals(AgentCli.EXIT_NOT_RUNNING, result.code)
        assertTrue(result.errors.contains("not running"))
    }

    @Test
    fun anUnknownCommandShowsTheUsage() {
        assertEquals(AgentCli.EXIT_USAGE, run("nothing").code)
        assertEquals(AgentCli.EXIT_USAGE, run("read").code)
    }

    @Test
    fun theDocumentsAllowedAreListedAndRead() {
        startHost()
        val id = newDocument()
        val listed = run("docs")
        assertEquals(AgentCli.EXIT_OK, listed.code, listed.errors)
        assertTrue(listed.output.contains(id))
        assertTrue(listed.output.contains("Physics"))

        val read = run("read", id)
        assertEquals(AgentCli.EXIT_OK, read.code, read.errors)
        assertTrue(read.output.contains("page 0"))

        assertEquals(AgentCli.EXIT_OK, run("read", id, "--page", "0").code)
        assertEquals(AgentCli.EXIT_NOT_FOUND, run("read", id, "--page", "9").code)
        assertEquals(AgentCli.EXIT_USAGE, run("read", id, "--page", "x").code)
    }

    @Test
    fun severalPagesAreReadAtOnceAndTheGuideIsPrinted() {
        startHost()
        val id = newDocument()
        val pages = run("read", id, "--pages", "0,0")
        assertEquals(AgentCli.EXIT_OK, pages.code, pages.errors)
        assertEquals(2, Regex("# Page 0").findAll(pages.output).count())
        assertEquals(AgentCli.EXIT_NOT_FOUND, run("read", id, "--pages", "0,9").code)
        assertEquals(AgentCli.EXIT_USAGE, run("read", id, "--pages", "one").code)

        val guide = run("guide")
        assertEquals(AgentCli.EXIT_OK, guide.code, guide.errors)
        assertTrue(guide.output.contains("# Writing in the notebook"))
    }

    @Test
    fun aDocumentNotAllowedHasItsExitCode() {
        startHost()
        assertEquals(AgentCli.EXIT_NOT_GRANTED, run("read", "elsewhere").code)
    }

    @Test
    fun aCommandChangesADocumentInNoEditorWhenTheUserChoseToApplyDirectly() {
        startHost()
        // Not allowed by itself: all the documents are
        val id = runBlocking { store.createDocument("Chemistry", 210f, 297f, PageBackground.Solid()).id }
        runBlocking {
            agents.access.setAllDocuments(true)
            agents.access.setApplyDirectly(true)
        }
        val items = File(directory, "items.json").apply {
            writeText("""[{"kind":"insert_text","markdown":"From the terminal"}]""")
        }
        val proposed = run("propose", id, "--file", items.path, "--title", "A note")
        assertEquals(AgentCli.EXIT_OK, proposed.code, proposed.errors)

        val status = run("status", proposed.output.trim())
        assertEquals(AgentCli.EXIT_OK, status.code, status.errors)
        assertTrue(status.output.contains("accepted"))
        val open = assertNotNull(runBlocking { documents.open(id) })
        assertEquals(listOf("From the terminal"), open.document.pages.first().texts.map { it.text })
        runBlocking { documents.close(open) }
        assertEquals(id, agents.activity.batches.value.single().documentId)
    }

    @Test
    fun theLibraryIsChangedReadAndUndoneFromTheTerminal() {
        startHost()
        val id = newDocument()
        runBlocking {
            agents.access.setAllDocuments(true)
            agents.access.setApplyDirectly(true)
        }
        val items = File(directory, "library.json").apply {
            writeText("""[{"kind":"create_folder","name":"Year 1"},{"kind":"trash_document","document":"$id"}]""")
        }
        val proposed = run("propose-library", "--file", items.path, "--title", "Tidy up")
        assertEquals(AgentCli.EXIT_OK, proposed.code, proposed.errors)
        assertEquals(AgentCli.EXIT_OK, run("status", proposed.output.trim()).code)

        val folders = run("folders")
        assertEquals(AgentCli.EXIT_OK, folders.code, folders.errors)
        assertTrue(folders.output.contains("Year 1"))
        val trash = run("trash")
        assertTrue(trash.output.contains(id), trash.output)

        val activity = run("activity")
        assertEquals(AgentCli.EXIT_OK, activity.code, activity.errors)
        val batch = activity.output.lines().first().substringBefore(" ")
        assertTrue(activity.output.contains("library"))
        val undone = run("undo", batch)
        assertEquals(AgentCli.EXIT_OK, undone.code, undone.errors)
        assertTrue(runBlocking { store.trash.first() }.none { it.id == id })
        assertEquals(AgentCli.EXIT_NOT_FOUND, run("undo", batch).code)
        assertEquals(AgentCli.EXIT_NOT_FOUND, run("undo", "elsewhere").code)
    }

    @Test
    fun anyToolIsCalledWithItsArgumentsInAFile() {
        startHost()
        val id = newDocument()
        val arguments = File(directory, "arguments.json").apply { writeText("""{"document":"$id","index":0,"strokes":true}""") }
        val read = run("call", "read_page", "--file", arguments.path)
        assertEquals(AgentCli.EXIT_OK, read.code, read.errors)
        assertTrue(read.output.contains("\"stroke_list\""), read.output)
        assertEquals(AgentCli.EXIT_REFUSED, run("call", "no_such_tool").code)
    }

    @Test
    fun aProposalIsFollowedUntilDecided() {
        startHost()
        val id = newDocument()
        val items = File(directory, "items.json").apply {
            writeText("""[{"kind":"insert_text","markdown":"From the terminal"}]""")
        }
        val proposed = run("propose", id, "--file", items.path, "--title", "A note")
        assertEquals(AgentCli.EXIT_OK, proposed.code, proposed.errors)
        val proposal = proposed.output.trim()

        val waiting = run("status", proposal)
        assertEquals(AgentCli.EXIT_OK, waiting.code)
        assertTrue(waiting.output.contains("waiting"))

        val follower = thread {
            Thread.sleep(100)
            runBlocking { agents.decide(proposal, setOf(0)) }
        }
        val decided = run("status", proposal, "--wait")
        follower.join()
        assertEquals(AgentCli.EXIT_OK, decided.code, decided.errors)
        assertTrue(decided.output.contains("accepted"))
    }

    @Test
    fun aPreviewTellsWhereTheTextGoesAndProposesNothing() {
        startHost()
        val id = newDocument()
        val file = File(directory, "items.json").apply { writeText("""[{"kind":"insert_text","markdown":"New"}]""") }

        val preview = run("preview", id, "--file", file.path)

        assertEquals(0, preview.code, preview.errors)
        assertTrue("Item 0: insert_text" in preview.output, preview.output)
        assertTrue("page 0: x " in preview.output, preview.output)
        assertTrue(agents.proposals.proposals.value.isEmpty())
        assertTrue("\"pieces\"" in run("preview", id, "--file", file.path, "--json").output)
        // The application takes it for a command, not for something to open
        assertTrue("preview" in AgentCli.COMMANDS)
    }

    @Test
    fun aProposalRejectedHasItsExitCode() {
        startHost()
        val id = newDocument()
        val items = File(directory, "items.json").apply { writeText("""[{"kind":"add_page"}]""") }
        val proposal = run("propose", id, "--file", items.path).output.trim()
        runBlocking { agents.decide(proposal, emptySet()) }
        assertEquals(AgentCli.EXIT_NOT_ACCEPTED, run("status", proposal).code)
    }

    @Test
    fun aFileThatIsNotItemsIsRefused() {
        startHost()
        val id = newDocument()
        val broken = File(directory, "broken.json").apply { writeText("{not") }
        assertEquals(AgentCli.EXIT_REFUSED, run("propose", id, "--file", broken.path).code)
        assertEquals(AgentCli.EXIT_USAGE, run("propose", id, "--file", File(directory, "missing").path).code)
    }

    @Test
    fun theBridgeForwardsTheMessagesOfAClient() {
        startHost()
        newDocument()
        val toBridge = PipedOutputStream()
        val bridgeInput = PipedInputStream(toBridge, 1 shl 16)
        val fromBridge = PipedInputStream(1 shl 16)
        val bridgeOutput = PipedOutputStream(fromBridge)
        val bridge = thread {
            AgentCli(directory, PrintStream(bridgeOutput), PrintStream(ByteArrayOutputStream()), bridgeInput).run(listOf("mcp"))
            bridgeOutput.close()
        }
        val answers = fromBridge.bufferedReader()
        toBridge.write("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","clientInfo":{"name":"Claude","version":"1"}}}""".toByteArray() + '\n'.code.toByte())
        toBridge.flush()
        assertTrue(answers.readLine().contains("2025-11-25"))
        waitFor { agents.access.connections.value.any { it.name == "Claude" } }

        toBridge.write("""{"jsonrpc":"2.0","method":"notifications/initialized"}""".toByteArray() + '\n'.code.toByte())
        toBridge.write("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_documents"}}""".toByteArray() + '\n'.code.toByte())
        toBridge.flush()
        val listed = answers.readLine()
        assertTrue(listed.contains("Physics"), listed)
        assertTrue(listed.contains("\"id\":2"))

        toBridge.close()
        bridge.join(10_000)
        assertFalse(bridge.isAlive)
        waitFor { agents.access.connections.value.isEmpty() }
    }

    /** A bridge of a client of the protocol, its lines written and read by the test. */
    private inner class Bridge {
        private val toBridge = PipedOutputStream()
        private val fromBridge = PipedInputStream(1 shl 16)
        private val bridgeOutput = PipedOutputStream(fromBridge)
        private val bridgeInput = PipedInputStream(toBridge, 1 shl 16)
        val answers = fromBridge.bufferedReader()
        val thread = thread {
            AgentCli(directory, PrintStream(bridgeOutput), PrintStream(ByteArrayOutputStream()), bridgeInput, waitIntervalMs = 20, bridgeWaitMs = 300).run(listOf("mcp"))
            bridgeOutput.close()
        }

        fun send(line: String) {
            toBridge.write(line.toByteArray() + '\n'.code.toByte())
            toBridge.flush()
        }

        /** Sends [line] and reads its answer. */
        fun ask(line: String): String {
            send(line)
            return answers.readLine() ?: error("The bridge ended")
        }

        fun end() {
            toBridge.close()
            thread.join(10_000)
            assertFalse(thread.isAlive)
        }
    }

    private val initialize = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","clientInfo":{"name":"Claude","version":"1"}}}"""
    private val initialized = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""

    private fun listDocuments(id: Int) = """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"list_documents"}}"""

    @Test
    fun theBridgeStaysWhenTheApplicationIsNotRunningAndSaysSo() {
        val bridge = Bridge()
        val opened = bridge.ask(initialize)
        assertTrue(opened.contains("\"id\":1") && opened.contains("2025-11-25") && opened.contains("not running"), opened)
        bridge.send(initialized)
        val refused = bridge.ask(listDocuments(2))
        assertTrue(refused.contains("\"id\":2") && refused.contains("\"error\"") && refused.contains("not running"), refused)
        assertTrue(bridge.ask("""{"jsonrpc":"2.0","id":3,"method":"ping"}""").contains("\"result\""))
        assertTrue(bridge.thread.isAlive)
        bridge.end()
    }

    @Test
    fun theBridgeFindsTheApplicationStartedAfterIt() {
        val bridge = Bridge()
        assertTrue(bridge.ask(initialize).contains("not running"))
        bridge.send(initialized)

        startHost()
        newDocument()
        // Told by itself that the tools are there now
        assertTrue(bridge.answers.readLine().contains("notifications/tools/list_changed"))
        waitFor { agents.access.connections.value.any { it.name == "Claude" } }
        val listed = bridge.ask(listDocuments(2))
        assertTrue(listed.contains("Physics") && listed.contains("\"id\":2"), listed)
        bridge.end()
    }

    @Test
    fun theBridgeOutlivesTheApplicationRestarted() {
        startHost()
        newDocument()
        val bridge = Bridge()
        assertTrue(bridge.ask(initialize).contains("2025-11-25"))
        bridge.send(initialized)
        assertTrue(bridge.ask(listDocuments(2)).contains("Physics"))

        // The application quits and starts again: another host on the same directory
        host.stop()
        waitFor { agents.access.connections.value.isEmpty() }
        val again = AgentHost(directory, agents.access, AgentEndpoint(agents, "PencilNotes", "test"))
        again.start()
        try {
            waitFor { AgentSocket.fileOf(directory).exists() }
            // A request with no version is taken only from a client that opened with initialize: it was sent again
            val listed = bridge.ask(listDocuments(3))
            assertTrue(listed.contains("Physics") && listed.contains("\"id\":3"), listed)
            waitFor { agents.access.connections.value.any { it.name == "Claude" } }
            assertTrue(bridge.thread.isAlive)
            bridge.end()
        } finally {
            again.stop()
        }
    }

    @Test
    fun theBridgeSaysSoWhenTheApplicationWentAwayAndComesBackWithIt() {
        startHost()
        newDocument()
        val bridge = Bridge()
        bridge.ask(initialize)
        bridge.send(initialized)
        host.stop()
        waitFor { agents.access.connections.value.isEmpty() }
        val refused = bridge.ask(listDocuments(2))
        assertTrue(refused.contains("\"id\":2") && refused.contains("not running"), refused)

        val again = AgentHost(directory, agents.access, AgentEndpoint(agents, "PencilNotes", "test"))
        again.start()
        try {
            waitFor { AgentSocket.fileOf(directory).exists() }
            assertTrue(bridge.ask(listDocuments(3)).contains("Physics"))
            bridge.end()
        } finally {
            again.stop()
        }
    }

    @Test
    fun theBridgeAnswersWhatWasAskedBeforeItsInputEnded() {
        startHost()
        val meta = """"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28"}"""
        val messages = (1..20).joinToString("") { """{"jsonrpc":"2.0","id":$it,"method":"tools/list","params":{$meta}}""" + "\n" }
        val result = run("mcp", input = messages.toByteArray())
        assertEquals(AgentCli.EXIT_OK, result.code, result.errors)
        assertEquals(20, result.output.lines().count { it.isNotBlank() })
    }

    @Test
    fun aLineTooLongEndsTheConnectionAndNothingElse() {
        startHost()
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        channel.connect(AgentSocket.addressOf(AgentSocket.fileOf(directory)))
        val chunk = ByteArray(1 shl 20) { 'x'.code.toByte() }
        val ended = try {
            repeat(AgentEndpoint.MAX_MESSAGE_LENGTH / chunk.size + 2) {
                val buffer = ByteBuffer.wrap(chunk)
                while (buffer.hasRemaining()) channel.write(buffer)
            }
            channel.read(ByteBuffer.allocate(16)) < 0
        } catch (_: java.io.IOException) {
            true
        }
        channel.close()
        assertTrue(ended)
        // The application goes on
        assertEquals(AgentCli.EXIT_OK, run("docs").code)
    }

    @Test
    fun whatIsNotUnderstoodDoesNotEndTheHost() {
        startHost()
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        channel.connect(AgentSocket.addressOf(AgentSocket.fileOf(directory)))
        val message = ("[".repeat(200_000) + "\n").toByteArray()
        val buffer = ByteBuffer.wrap(message)
        while (buffer.hasRemaining()) channel.write(buffer)
        val answer = ByteBuffer.allocate(4096)
        channel.read(answer)
        channel.close()
        assertTrue(String(answer.array(), 0, answer.position()).contains("-32600"))
        assertEquals(AgentCli.EXIT_OK, run("docs").code)
    }

    @Test
    fun theAccessTurnedOffClosesTheSocket() {
        startHost()
        assertEquals(AgentCli.EXIT_OK, run("docs").code)
        runBlocking { agents.access.setEnabled(false) }
        waitFor { !AgentSocket.fileOf(directory).exists() }
        assertEquals(AgentCli.EXIT_NOT_RUNNING, run("docs").code)
    }

    @Test
    fun theSocketIsInADirectoryOfTheUserOnly() {
        startHost()
        val folder = AgentSocket.fileOf(directory).parentFile.toPath()
        val permissions = Files.getPosixFilePermissions(folder)
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
            permissions
        )
    }

    @Test
    fun aDataDirectoryTooLongForASocketUsesAPrivateOne() {
        val deep = File(directory, "a".repeat(120))
        val socket = AgentSocket.fileOf(deep)
        assertTrue(socket.path.toByteArray().size <= AgentSocket.MAX_PATH_BYTES, socket.path)
        assertEquals(socket, AgentSocket.fileOf(deep))
        assertFalse(socket.path.contains("aaaa"))
        assertNotNull(socket.parentFile)
        val long = AgentHost(deep, agents.access, AgentEndpoint(agents, "PencilNotes", "test"))
        try {
            long.start()
            runBlocking { agents.access.setEnabled(true) }
            waitFor { socket.exists() }
            assertEquals(AgentCli.EXIT_OK, AgentCli(deep, PrintStream(ByteArrayOutputStream()), PrintStream(ByteArrayOutputStream())).run(listOf("docs")))
        } finally {
            long.stop()
            socket.parentFile.deleteRecursively()
        }
    }

    @Test
    fun aLineIsBounded() {
        val reader = BoundedLineReader(ByteArrayInputStream("short\nlonger than ten\n".toByteArray()), 10)
        assertEquals("short", reader.readLine())
        val failure = runCatching { reader.readLine() }.exceptionOrNull()
        assertTrue(failure is LineTooLongException)
    }

    @Test
    fun aLineReaderEndsWithTheInput() {
        val reader = BoundedLineReader(ByteArrayInputStream("one\ntwo\n".toByteArray()), 10)
        assertEquals("one", reader.readLine())
        assertEquals("two", reader.readLine())
        assertEquals(null, reader.readLine())
    }

    @Test
    fun theDocumentsAreReadThroughTheStoreOfTheApplicationOnly() = runBlocking {
        // A command touches no file of the data directory but its socket
        startHost()
        run("docs")
        assertEquals(setOf("agent"), directory.list()!!.toSet().minus("items.json"))
        assertTrue(store.documents.first().isEmpty())
    }
}
