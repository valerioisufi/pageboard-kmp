package io.github.valerioisufi.pencilnotes

import io.github.valerioisufi.pageboard.agent.AgentJournal
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The proposals of the clients and what the models changed, kept in a file of the data directory
 * (`agent/journal.json`): written to a file beside it and moved over it, so that it is whole or as it
 * was. A file that is not understood, or too long to be one, is moved to `journal-unread-<time>.json`
 * and left there. See `docs/plans/agent-ergonomics.md`.
 */
internal class FileAgentJournal(dataDirectory: File, private val clock: () -> Long = System::currentTimeMillis) : AgentJournal {
    private val directory = File(dataDirectory.absoluteFile, "agent")
    private val file = File(directory, FILE_NAME)

    override suspend fun load(): String? = withContext(Dispatchers.IO) {
        try {
            when {
                !file.isFile -> null
                file.length() > MAX_BYTES -> {
                    aside()
                    null
                }
                else -> file.readText(StandardCharsets.UTF_8)
            }
        } catch (_: IOException) {
            null
        }
    }

    override suspend fun save(text: String): Unit = withContext(Dispatchers.IO) {
        try {
            directory.mkdirs()
            val written = File(directory, "$FILE_NAME.new")
            written.writeText(text, StandardCharsets.UTF_8)
            try {
                Files.move(written.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(written.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (_: IOException) {
            // Not kept this time: the documents are what matters, and they are in their store
        }
    }

    override suspend fun setAside(): Unit = withContext(Dispatchers.IO) { aside() }

    private fun aside() {
        if (!file.isFile) return
        try {
            var target = File(directory, "journal-unread-${clock()}.json")
            var attempt = 1
            while (target.exists()) target = File(directory, "journal-unread-${clock()}-${attempt++}.json")
            Files.move(file.toPath(), target.toPath())
        } catch (_: IOException) {
            // Still there under its name: nothing is written over it while it is not read (see ExternalAgents)
        }
    }

    private companion object {
        const val FILE_NAME = "journal.json"

        /** Four bytes a character at most. */
        const val MAX_BYTES = AgentJournal.MAX_LENGTH.toLong() * 4
    }
}
