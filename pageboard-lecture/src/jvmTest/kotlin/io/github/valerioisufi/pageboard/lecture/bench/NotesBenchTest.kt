package io.github.valerioisufi.pageboard.lecture.bench

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** [NotesBench] without a model: the lectures read from the workspace, the runs kept, the cap. */
class NotesBenchTest {
    private val root: File = Files.createTempDirectory("notes-bench").toFile()
    private val workspace = File(root, "workspace")
    private val directory = File(root, "bench")

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    /** A lecture of one sentence in [workspace], with its copy in the snapshot. */
    private fun lecture(id: String) {
        File(workspace, "lectures/$id").mkdirs()
        File(workspace, "lectures/$id/prepared.json").writeText(
            """{"lectureId":"$id","documentId":"d","durationMs":60000,"context":{"sentences":[{"startMs":0,"endMs":1000,"text":"una frase"}]}}"""
        )
        File(workspace, "snapshot/lectures/doc-d/$id").mkdirs()
        File(workspace, "snapshot/lectures/doc-d/$id/lecture.json").writeText("""{"pages":[]}""")
    }

    private fun bench(ids: List<String>, config: String, maxDollars: Double = 6.0) =
        NotesBench(Bench(directory, listOf("none")), workspace, configs = listOf(config), lectureIds = ids, maxDollars = maxDollars)

    @Test
    fun aLectureIsReadFromTheSnapshotOfTheWorkspace() {
        lecture("one")
        val read = BenchLecture.read(workspace, File(directory, "material/pencilnotes"), "one")
        assertNotNull(read)
        assertEquals("one", read.id)
    }

    @Test
    fun aRunAlreadyWrittenIsNotAskedAgainForEachLecture() = runBlocking {
        lecture("one")
        lecture("two")
        val config = "gemini-3.8-flash-cached-text#2"
        for (id in listOf("one", "two")) {
            File(directory, "answers/notes/$id").mkdirs()
            File(directory, "answers/notes/$id/$config.json").writeText(
                """{"config":"$config","lectureId":"$id","intervalMs":300000,"requests":3,"failed":0,"millis":1,"promptTokens":1,"outputTokens":1,"promptByModality":{},"outline":"","notes":[]}"""
            )
        }
        val report = StringBuilder()
        bench(listOf("one", "two"), config).run(report)
        assertTrue("`one`" in report.toString() && "`two`" in report.toString(), report.toString())
        assertEquals(2, Regex("""\| ${Regex.escape(config)} \| 300 s \| 3 \|""").findAll(report).count(), report.toString())
    }

    @Test
    fun aRunIsCheckedWithoutAskingOrPaying() = runBlocking {
        lecture("one")
        val base = "e4b-text"
        File(directory, "answers/notes/one").mkdirs()
        val note = """{"atMs":0,"page":null,"kind":"EXPLANATION","title":"t","content":"la coda dei processi cresce sempre"}"""
        File(directory, "answers/notes/one/$base.json").writeText(
            """{"config":"$base","lectureId":"one","intervalMs":300000,"requests":3,"failed":1,"millis":9,"longestMillis":5,"promptTokens":0,"outputTokens":0,"promptByModality":{},"outline":"","notes":[$note,$note]}"""
        )
        bench(listOf("one"), "$base+repeat").run(StringBuilder())
        val written = File(directory, "answers/notes/one/$base+repeat.json").readText()
        assertEquals(1, Regex("\"atMs\"").findAll(written).count(), written)
        assertTrue("\"longestMillis\": 5" in written, written)
        assertTrue("repeats a note" in File(directory, "answers/notes/one/$base+repeat.md").readText())
        assertFalse(File(directory, "dollars.txt").exists())
    }

    @Test
    fun aCheckWithoutItsRunSaysSo() = runBlocking {
        lecture("one")
        val report = StringBuilder()
        bench(listOf("one"), "e4b-text+repeat").run(report)
        assertTrue("no run of e4b-text to check" in report.toString(), report.toString())
        assertFalse(File(directory, "answers/notes/one/e4b-text+repeat.json").exists())
    }

    @Test
    fun theCapCountsWhatWasSpentBeforeAndARunStoppedIsNotKept() = runBlocking {
        lecture("one")
        directory.mkdirs()
        File(directory, "dollars.txt").writeText("6.5")
        val report = StringBuilder()
        bench(listOf("one"), "gemini-3.8-flash-text").run(report)
        assertTrue("stopped at 6.50 dollars" in report.toString(), report.toString())
        assertFalse(File(directory, "answers/notes/one/gemini-3.8-flash-text.json").exists())
        assertEquals("6.5", File(directory, "dollars.txt").readText())
    }
}
