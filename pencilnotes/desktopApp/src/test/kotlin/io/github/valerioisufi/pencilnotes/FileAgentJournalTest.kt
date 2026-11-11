package io.github.valerioisufi.pencilnotes

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class FileAgentJournalTest {
    private fun directory(): File = Files.createTempDirectory("pencilnotes-journal").toFile().also { it.deleteOnExit() }

    @Test
    fun whatIsSavedIsLoadedByTheNextRun() = runBlocking {
        val data = directory()
        assertNull(FileAgentJournal(data).load())
        FileAgentJournal(data).save("first")
        FileAgentJournal(data).save("second, with è and 日本")
        assertEquals("second, with è and 日本", FileAgentJournal(data).load())
        // Nothing is left beside it
        assertEquals(listOf("journal.json"), File(data, "agent").list()!!.toList())
    }

    @Test
    fun whatIsPutAsideIsKeptAsItIsUnderAnotherName() = runBlocking {
        val data = directory()
        var time = 100L
        val journal = FileAgentJournal(data) { time }
        journal.save("of a later version")
        journal.setAside()
        assertNull(journal.load())
        journal.save("new")
        // A second one in the same instant does not take the place of the first
        journal.setAside()
        journal.setAside()
        val agent = File(data, "agent")
        assertEquals(listOf("journal-unread-100-1.json", "journal-unread-100.json"), agent.list()!!.sorted())
        assertEquals("of a later version", File(agent, "journal-unread-100.json").readText())
        assertEquals("new", File(agent, "journal-unread-100-1.json").readText())
        assertFalse(File(agent, "journal.json").exists())
        time = 200L
    }
}
