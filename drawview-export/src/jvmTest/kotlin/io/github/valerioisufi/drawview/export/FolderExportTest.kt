package io.github.valerioisufi.drawview.export

import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.pdf.PdfiumEngine
import io.github.valerioisufi.drawview.store.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.store.KeyValueDocumentStore
import io.github.valerioisufi.drawview.store.OpenDocuments
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone

class FolderExportTest {

    @Test
    fun crc32MatchesStandardJavaCrc32() {
        val testData = listOf(
            ByteArray(0),
            "Hello, world!".encodeToByteArray(),
            "123456789".encodeToByteArray(),
            ByteArray(1024) { (it % 256).toByte() }
        )
        for (bytes in testData) {
            val expected = CRC32().apply { update(bytes) }.value
            val actual = Crc32.compute(bytes)
            assertEquals(expected, actual, "CRC32 mismatch for data of length ${bytes.size}")
        }
    }

    @Test
    fun zipWriterProducesValidZipReadableByJavaZipInputStream() {
        val zipWriter = ZipWriter()
        val file1Data = "Hello from document 1".encodeToByteArray()
        val file2Data = "Greetings from subfolder document 2".encodeToByteArray()

        zipWriter.addEntry("Doc1.txt", file1Data)
        zipWriter.addEntry("Subfolder/Doc2.txt", file2Data)

        val zipBytes = zipWriter.toByteArray()
        assertTrue(zipBytes.isNotEmpty(), "ZIP bytes should not be empty")

        val readEntries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                readEntries[entry.name] = zis.readBytes()
                zis.closeEntry()
            }
        }

        assertEquals(2, readEntries.size)
        assertTrue("Doc1.txt" in readEntries)
        assertTrue("Subfolder/Doc2.txt" in readEntries)
        assertEquals(file1Data.decodeToString(), readEntries["Doc1.txt"]?.decodeToString())
        assertEquals(file2Data.decodeToString(), readEntries["Subfolder/Doc2.txt"]?.decodeToString())
    }

    @Test
    fun folderDocumentsResolvesHierarchyAndHandlesDuplicates() = runBlocking {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage())
        val openDocuments = OpenDocuments(store, Dispatchers.Default)
        val export = DocumentExport(openDocuments)

        // Root note
        store.createDocument("RootNote", folderId = null).also { store.closeDocument(it.id) }

        // Folder "Math"
        store.createFolder("Math", parentId = null)
        val mathFolder = store.folders.first().first { it.name == "Math" }
        store.createDocument("Calculus", folderId = mathFolder.id).also { store.closeDocument(it.id) }
        store.createDocument("Calculus", folderId = mathFolder.id).also { store.closeDocument(it.id) } // duplicate name

        // Subfolder "Math/Geometry"
        store.createFolder("Geometry", parentId = mathFolder.id)
        val geometryFolder = store.folders.first().first { it.name == "Geometry" }
        store.createDocument("Topology", folderId = geometryFolder.id).also { store.closeDocument(it.id) }

        // Another root folder "Physics"
        store.createFolder("Physics", parentId = null)
        val physicsFolder = store.folders.first().first { it.name == "Physics" }
        store.createDocument("Mechanics", folderId = physicsFolder.id).also { store.closeDocument(it.id) }

        // Test 1: Home without subfolders -> only RootNote
        val homeDirect = export.folderDocuments(null, includeSubfolders = false)
        assertEquals(1, homeDirect.size)
        assertEquals("RootNote.pdf", homeDirect.single().relativePath)

        // Test 2: Home with subfolders -> all 5 notes
        val homeAll = export.folderDocuments(null, includeSubfolders = true)
        assertEquals(5, homeAll.size)
        val homePaths = homeAll.map { it.relativePath }.toSet()
        assertTrue("RootNote.pdf" in homePaths)
        assertTrue("Math/Calculus.pdf" in homePaths)
        assertTrue("Math/Calculus (2).pdf" in homePaths)
        assertTrue("Math/Geometry/Topology.pdf" in homePaths)
        assertTrue("Physics/Mechanics.pdf" in homePaths)

        // Test 3: Math without subfolders -> only Calculus and Calculus (2)
        val mathDirect = export.folderDocuments(mathFolder.id, includeSubfolders = false)
        assertEquals(2, mathDirect.size)
        val mathDirectPaths = mathDirect.map { it.relativePath }.toSet()
        assertTrue("Calculus.pdf" in mathDirectPaths)
        assertTrue("Calculus (2).pdf" in mathDirectPaths)

        // Test 4: Math with subfolders -> Calculus, Calculus (2), and Geometry/Topology
        val mathAll = export.folderDocuments(mathFolder.id, includeSubfolders = true)
        assertEquals(3, mathAll.size)
        val mathAllPaths = mathAll.map { it.relativePath }.toSet()
        assertTrue("Calculus.pdf" in mathAllPaths)
        assertTrue("Calculus (2).pdf" in mathAllPaths)
        assertTrue("Geometry/Topology.pdf" in mathAllPaths)
    }

    @Test
    fun exportFolderAsZipAndCombinedPdf() = runBlocking {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage())
        val openDocuments = OpenDocuments(store, Dispatchers.Default)
        val export = DocumentExport(openDocuments)
        val pdfExporter = PdfExporter(textAdapter = null)

        val doc1 = store.createDocument("Note 1", widthMm = 210f, heightMm = 297f, background = PageBackground.Solid()).also { store.closeDocument(it.id) }
        val doc2 = store.createDocument("Note 2", widthMm = 210f, heightMm = 297f, background = PageBackground.Solid()).also { store.closeDocument(it.id) }

        val tempDir = File.createTempFile("export_test", "").apply { delete(); mkdirs() }
        try {
            // Test ZIP export
            val zipPath = File(tempDir, "archive.zip").absolutePath
            val home = export.resolve(ExportScope.Folder(null))
            val zipResult = export.export(home, ExportFormat.PDF_PER_DOCUMENT, ExportOptions(), ExportWriters(pdfExporter), zipPath)

            assertEquals(2, zipResult.documents)
            assertEquals(2, zipResult.pages)
            assertEquals(0, zipResult.skipped)

            val zipFile = File(zipPath)
            assertTrue(zipFile.exists() && zipFile.length() > 0)

            val entries = mutableMapOf<String, ByteArray>()
            ZipInputStream(zipFile.inputStream()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    entries[entry.name] = zis.readBytes()
                    zis.closeEntry()
                }
            }
            assertEquals(2, entries.size)
            assertTrue("Note 1.pdf" in entries)
            assertTrue("Note 2.pdf" in entries)
            assertTrue(entries["Note 1.pdf"]!!.size > 100)

            // Test Combined PDF export
            val pdfPath = File(tempDir, "combined.pdf").absolutePath
            val pdfResult = export.export(home, ExportFormat.COMBINED_PDF, ExportOptions(), ExportWriters(pdfExporter), pdfPath)

            assertEquals(2, pdfResult.documents)
            assertEquals(2, pdfResult.pages)
            assertEquals(0, pdfResult.skipped)

            val pdfFile = File(pdfPath)
            assertTrue(pdfFile.exists() && pdfFile.length() > 0)

            // Verify with PDFium that the combined PDF has 2 pages
            PdfiumEngine.open(pdfPath).use { document ->
                assertEquals(2, document.pageCount)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun zipEntriesKeepTheirDatesInEveryField() {
        // 2026-03-14 15:09:27.123 UTC, 16:09:27 in Rome
        val modified = 1_773_500_967_123L
        val created = 1_700_000_000_456L
        val zip = ZipWriter(TimeZone.of("Europe/Rome"))
        zip.addDirectory("Math", ZipEntryTimes(created + 1000, created))
        zip.addEntry("Math/Calculus.pdf", "pdf".encodeToByteArray(), ZipEntryTimes(modified, created))
        zip.addEntry("Undated.txt", "x".encodeToByteArray())
        val bytes = zip.toByteArray()

        // The DOS fields of the first local header after the directory one, in the zone given
        val second = bytes.indexOfHeader(1)
        val dosTime = bytes.shortAt(second + 10)
        val dosDate = bytes.shortAt(second + 12)
        assertEquals(16, dosTime shr 11)
        assertEquals(9, (dosTime shr 5) and 0x3F)
        assertEquals(13, (dosTime and 0x1F)) // 27 s in steps of 2
        assertEquals(2026 - 1980, dosDate shr 9)
        assertEquals(3, (dosDate shr 5) and 0x0F)
        assertEquals(14, dosDate and 0x1F)

        withZipFile(bytes) { file ->
            val entry = file.getEntry("Math/Calculus.pdf")
            assertEquals(modified, entry.lastModifiedTime.toMillis())
            assertEquals(created, entry.creationTime.toMillis())
            assertEquals("pdf", file.getInputStream(entry).readBytes().decodeToString())

            val directory = file.getEntry("Math/")
            assertTrue(directory.isDirectory)
            assertEquals(created + 1000, directory.lastModifiedTime.toMillis())

            val undated = file.getEntry("Undated.txt")
            assertEquals(null, undated.creationTime)
            assertEquals(0, undated.extra?.size ?: 0)
        }
    }

    @Test
    fun zipDatesOutsideDosAreHeldWithinIt() {
        val zip = ZipWriter(TimeZone.UTC)
        zip.addEntry("Old.txt", ByteArray(0), ZipEntryTimes(0L))
        val bytes = zip.toByteArray()
        assertEquals(0, bytes.shortAt(10))
        assertEquals(0x21, bytes.shortAt(12))
        withZipFile(bytes) { file ->
            // The extra fields keep the real date
            assertEquals(0L, file.getEntry("Old.txt").lastModifiedTime.toMillis())
        }
    }

    @Test
    fun exportedArchiveIsDatedByTheNotesAndTheirFolders() = runBlocking {
        var now = 1_760_000_000_000L
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), clock = { now })
        val export = DocumentExport(OpenDocuments(store, Dispatchers.Default))
        store.createFolder("Math", parentId = null)
        val math = store.folders.first().single()
        now += 60_000
        store.createFolder("Geometry", parentId = math.id)
        val geometry = store.folders.first().single { it.name == "Geometry" }
        now += 60_000
        store.createDocument("Loose", folderId = null).also { store.closeDocument(it.id) }
        now += 60_000
        store.createDocument("Topology", folderId = geometry.id).also { store.closeDocument(it.id) }
        now += 60_000
        store.createDocument("Algebra", folderId = math.id).also { store.closeDocument(it.id) }
        val summaries = store.documents.first().associateBy { it.name }

        val contents = export.resolve(ExportScope.Items(listOf(summaries.getValue("Loose").id), listOf(math.id)))
        assertEquals(listOf("Math", "Math/Geometry"), contents.folders.keys.toList())

        val tempDir = File.createTempFile("export_dates", "").apply { delete(); mkdirs() }
        try {
            val path = File(tempDir, "notes.zip").absolutePath
            export.export(contents, ExportFormat.PDF_PER_DOCUMENT, ExportOptions(), ExportWriters(PdfExporter(textAdapter = null)), path)
            ZipFile(path).use { file ->
                // In the order of the tree, each directory just before what it holds
                assertEquals(
                    listOf("Loose.pdf", "Math/", "Math/Algebra.pdf", "Math/Geometry/", "Math/Geometry/Topology.pdf"),
                    file.entries().toList().map { it.name }
                )
                for ((name, summary) in listOf("Loose.pdf" to summaries.getValue("Loose"), "Math/Geometry/Topology.pdf" to summaries.getValue("Topology"))) {
                    val entry = assertNotNull(file.getEntry(name), name)
                    assertEquals(summary.modifiedAt, entry.lastModifiedTime.toMillis(), name)
                    assertEquals(summary.createdAt, entry.creationTime.toMillis(), name)
                }
                val folders = store.folders.first().associateBy { it.name }
                for ((name, folder) in listOf("Math/" to folders.getValue("Math"), "Math/Geometry/" to folders.getValue("Geometry"))) {
                    val entry = assertNotNull(file.getEntry(name), name)
                    assertTrue(entry.isDirectory, name)
                    assertEquals(folder.modifiedAt, entry.lastModifiedTime.toMillis(), name)
                }
                assertFalse(file.entries().toList().any { it.lastModifiedTime.toMillis() < now - 10 * 60_000 })
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /** The offset of the [index]-th local file header of the archive. */
    private fun ByteArray.indexOfHeader(index: Int): Int {
        var found = -1
        var offset = 0
        while (offset + 4 <= size) {
            if (this[offset] == 0x50.toByte() && this[offset + 1] == 0x4b.toByte() && this[offset + 2] == 3.toByte() && this[offset + 3] == 4.toByte()) {
                if (++found == index) return offset
            }
            offset++
        }
        error("No local header $index")
    }

    private fun ByteArray.shortAt(offset: Int): Int = (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun withZipFile(bytes: ByteArray, block: (ZipFile) -> Unit) {
        val file = File.createTempFile("dates", ".zip")
        try {
            file.writeBytes(bytes)
            ZipFile(file).use(block)
        } finally {
            file.delete()
        }
    }
}
