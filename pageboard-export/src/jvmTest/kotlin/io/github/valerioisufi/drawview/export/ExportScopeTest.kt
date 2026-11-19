package io.github.valerioisufi.drawview.export

import io.github.valerioisufi.drawview.model.change.AddPage
import io.github.valerioisufi.drawview.model.change.PageData
import io.github.valerioisufi.drawview.pdf.PdfiumEngine
import io.github.valerioisufi.drawview.store.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.store.KeyValueDocumentStore
import io.github.valerioisufi.drawview.store.OpenDocuments
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class ExportScopeTest {
    private val store = KeyValueDocumentStore(InMemoryKeyValueStorage())
    private val documents = OpenDocuments(store, Dispatchers.Default)
    private val export = DocumentExport(documents)

    private suspend fun note(name: String, folderId: String? = null, pages: Int = 1): String {
        val id = store.createDocument(name, folderId = folderId).id
        store.closeDocument(id)
        if (pages > 1) {
            val document = checkNotNull(documents.open(id))
            document.edit { session ->
                for (index in 1 until pages) {
                    session.submit(AddPage(PageData("$id-$index", session.document.orderKeyAt(index), 210f, 297f)))
                }
            }
            document.flush()
            documents.close(document)
        }
        return id
    }

    private suspend fun folder(name: String, parentId: String? = null): String {
        store.createFolder(name, parentId)
        return store.folders.first().first { it.name == name }.id
    }

    private fun zipEntries(path: String): Map<String, ByteArray> = buildMap {
        ZipInputStream(File(path).inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes())
            }
        }
    }

    private fun <T> withTempDir(block: (File) -> T): T {
        val dir = File.createTempFile("export_scope", "").apply { delete(); mkdirs() }
        return try { block(dir) } finally { dir.deleteRecursively() }
    }

    @Test
    fun theSelectionOfTheLibraryIsAScope() {
        assertIs<ExportScope.Pages>(ExportScope.ofSelection(listOf("a"), emptyList()))
        assertIs<ExportScope.Folder>(ExportScope.ofSelection(emptyList(), listOf("f")))
        assertIs<ExportScope.Items>(ExportScope.ofSelection(listOf("a", "b"), emptyList()))
        assertIs<ExportScope.Items>(ExportScope.ofSelection(listOf("a"), listOf("f")))
    }

    @Test
    fun theItemsChosenTogetherKeepTheirFoldersOnce() = runBlocking {
        val math = folder("Math")
        val geometry = folder("Geometry", parentId = math)
        val calculus = note("Calculus", math)
        note("Topology", geometry)
        val loose = note("Loose", pages = 3)

        val contents = export.resolve(ExportScope.Items(listOf(loose, calculus), listOf(math)))
        assertNull(contents.name)
        assertEquals(listOf("Loose.pdf", "Calculus.pdf", "Math/Geometry/Topology.pdf"), contents.documents.map { it.relativePath })
        assertEquals(5, contents.pages)
        assertEquals(listOf(ExportFormat.PDF_PER_DOCUMENT, ExportFormat.COMBINED_PDF, ExportFormat.ARCHIVE), contents.formats)

        val withoutSubfolders = export.resolve(ExportScope.Items(emptyList(), listOf(math)), includeSubfolders = false)
        assertEquals(listOf("Math/Calculus.pdf"), withoutSubfolders.documents.map { it.relativePath })
    }

    @Test
    fun aNoteGivesItsNameItsPagesAndItsFormats() = runBlocking {
        val id = note("Lecture 1: Limits", pages = 4)
        val contents = export.resolve(ExportScope.Pages(id))
        assertEquals("Lecture 1: Limits", contents.name)
        assertEquals(4, contents.pages)
        assertEquals(listOf(ExportFormat.PDF, ExportFormat.IMAGES, ExportFormat.MARKDOWN, ExportFormat.ARCHIVE), contents.formats)
        assertEquals("Lecture 1_ Limits.pdf", contents.fileName(ExportFormat.PDF, fallbackName = "Notes"))
        assertEquals("Lecture 1_ Limits.zip", contents.fileName(ExportFormat.IMAGES, fallbackName = "Notes"))
        assertEquals("Lecture 1_ Limits.png", contents.fileName(ExportFormat.IMAGES, pages = 1, fallbackName = "Notes"))
        assertEquals("Home.zip", export.resolve(ExportScope.Folder(null)).fileName(ExportFormat.PDF_PER_DOCUMENT, fallbackName = "Home"))
    }

    @Test
    fun thePagesOfANoteAsImagesAreAZipOfOneImagePerPage() = runBlocking {
        val id = note("Sketches", pages = 3)
        val contents = export.resolve(ExportScope.Pages(id))
        withTempDir { dir ->
            val path = File(dir, "sketches.zip").absolutePath
            val fractions = mutableListOf<Float>()
            val outcome = runBlocking {
                export.export(contents, ExportFormat.IMAGES, ExportOptions(), ExportWriters(PdfExporter(null), PageImageExporter(null)), path) {
                    fractions += it.fraction
                }
            }
            assertEquals(3, outcome.pages)
            assertEquals(listOf("Sketches - 1.png", "Sketches - 2.png", "Sketches - 3.png"), zipEntries(path).keys.sorted())
            assertEquals(1f, fractions.last())

            // A range of pages
            val pdf = File(dir, "range.pdf").absolutePath
            runBlocking { export.export(contents, ExportFormat.PDF, ExportOptions(pageIndices = setOf(1, 2)), ExportWriters(PdfExporter(null)), pdf) }
            PdfiumEngine.open(pdf).use { assertEquals(2, it.pageCount) }
        }
    }

    @Test
    fun severalNotesTellWhichOneIsBeingRead() = runBlocking {
        val first = note("First")
        val second = note("Second", pages = 2)
        val contents = export.resolve(ExportScope.Items(listOf(first, second), emptyList()))
        withTempDir { dir ->
            val progress = mutableListOf<ExportProgress>()
            val path = File(dir, "notes.pdf").absolutePath
            val outcome = runBlocking {
                export.export(contents, ExportFormat.COMBINED_PDF, ExportOptions(), ExportWriters(PdfExporter(null)), path) { progress += it }
            }
            assertEquals(2, outcome.documents)
            assertEquals(3, outcome.pages)
            assertEquals(listOf("First", "Second"), progress.filter { it.fraction < 0.1f }.map { it.name })
            assertTrue(progress.all { it.items == 2 })
            PdfiumEngine.open(path).use { assertEquals(3, it.pageCount) }
        }
    }

    @Test
    fun nothingToExportAndUnwritableFilesAreTold() = runBlocking {
        val empty = folder("Empty")
        val contents = export.resolve(ExportScope.Folder(empty))
        val nothing = assertFailsWith<ExportException> {
            export.export(contents, ExportFormat.PDF_PER_DOCUMENT, ExportOptions(), ExportWriters(PdfExporter(null)), "/nowhere.zip")
        }
        assertEquals(ExportFailure.NOTHING_TO_EXPORT, nothing.failure)

        val id = note("Note")
        val unwritable = assertFailsWith<ExportException> {
            export.export(export.resolve(ExportScope.Pages(id)), ExportFormat.IMAGES, ExportOptions(), ExportWriters(PdfExporter(null), PageImageExporter(null)), "/no/such/dir/note.png")
        }
        assertEquals(ExportFailure.NOT_WRITABLE, unwritable.failure)

        assertEquals(ExportFailure.NO_SPACE, ExportFailure.of(IOException("No space left on device")))
        assertEquals(ExportFailure.NOT_WRITABLE, ExportFailure.of(RuntimeException(IOException("Permission denied"))))
        assertEquals(ExportFailure.UNKNOWN, ExportFailure.of(IllegalStateException("boom")))
    }
}
