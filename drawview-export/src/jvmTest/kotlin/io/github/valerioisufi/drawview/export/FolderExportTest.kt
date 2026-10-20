package io.github.valerioisufi.drawview.export

import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.pdf.PdfiumEngine
import io.github.valerioisufi.drawview.store.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.store.KeyValueDocumentStore
import io.github.valerioisufi.drawview.store.OpenDocuments
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

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
            val zipResult = export.exportFolder(
                folderId = null,
                folderName = "Home",
                format = FolderExportFormat.ZIP,
                options = FolderExportOptions(includeSubfolders = true),
                pdfExporter = pdfExporter,
                path = zipPath
            )

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
            val pdfResult = export.exportFolder(
                folderId = null,
                folderName = "Home",
                format = FolderExportFormat.COMBINED_PDF,
                options = FolderExportOptions(includeSubfolders = true),
                pdfExporter = pdfExporter,
                path = pdfPath
            )

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
}
