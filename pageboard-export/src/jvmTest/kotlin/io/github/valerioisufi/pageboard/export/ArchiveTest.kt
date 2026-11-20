package io.github.valerioisufi.pageboard.export

import io.github.valerioisufi.pageboard.model.ImageElement
import io.github.valerioisufi.pageboard.model.DocumentResource
import io.github.valerioisufi.pageboard.model.TextElement
import io.github.valerioisufi.pageboard.model.change.AddElements
import io.github.valerioisufi.pageboard.platform.PlatformFiles
import io.github.valerioisufi.pageboard.platform.newResourcePath
import io.github.valerioisufi.pageboard.store.InMemoryKeyValueStorage
import io.github.valerioisufi.pageboard.store.KeyValueDocumentStore
import io.github.valerioisufi.pageboard.store.OpenDocuments
import io.github.valerioisufi.pageboard.model.change.DocumentSnapshot
import io.github.valerioisufi.pageboard.model.change.snapshot
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** The archive of the application: notes written whole, and brought back as new notes (see `docs/plans/archive.md`). */
class ArchiveTest {

    private fun library(): Pair<KeyValueDocumentStore, OpenDocuments> {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage())
        return store to OpenDocuments(store, Dispatchers.Default)
    }

    /** A library with "Limits" (a text, an image) in "Math", and "Loose" at the root. */
    private suspend fun filled(): Pair<KeyValueDocumentStore, OpenDocuments> {
        val (store, documents) = library()
        store.createFolder("Math", parentId = null)
        val math = store.folders.first().single { it.name == "Math" }
        val limits = store.createDocument("Limits", folderId = math.id).also { store.closeDocument(it.id) }
        store.createDocument("Loose", folderId = null).also { store.closeDocument(it.id) }
        val image = newResourcePath("archive_test", "png")
        PlatformFiles.writeBytes(image, byteArrayOf(1, 2, 3, 4))
        val open = checkNotNull(documents.open(limits.id))
        open.edit { session ->
            val document = session.document
            val resource = DocumentResource("res-1", DocumentResource.ResourceType.IMAGE, image)
            document.addResource(resource)
            val page = document.pages.first()
            val text = TextElement().apply { text = "# Limiti\n\$x \\to 0\$"; x = 10f; y = 10f; width = 100f; fontSize = 12f }
            val picture = ImageElement().apply { resourceId = resource.id; x = 10f; y = 40f; width = 30f; height = 20f }
            session.submit(AddElements.of(page, listOf(text, picture)))
        }
        documents.close(open)
        return store to documents
    }

    @Test
    fun notesWrittenInAnArchiveComeBackAsNewNotesInANewFolder() = runBlocking {
        val (source, sourceDocuments) = filled()
        val items = DocumentExport(sourceDocuments).folderDocuments(null, includeSubfolders = true)
        val archive = ArchiveExporter(sourceDocuments).write(items)
        assertEquals(2, archive.documents)
        assertTrue(archive.missing.isEmpty())

        // Into another library that has a note already
        val (target, targetDocuments) = library()
        val kept = target.createDocument("Mine", folderId = null).also { target.closeDocument(it.id) }
        val result = ArchiveImporter(targetDocuments).import(archive.bytes, "Backup")
        assertEquals(2, result.documents)

        val folders = target.folders.first()
        val backup = folders.single { it.name == "Backup" && it.parentId == null }
        val math = folders.single { it.name == "Math" }
        assertEquals(backup.id, math.parentId, "the folders made again under the new one")
        val notes = target.documents.first()
        assertEquals(setOf("Mine", "Limits", "Loose"), notes.map { it.name }.toSet())
        assertEquals(kept.id, notes.single { it.name == "Mine" }.id, "the note there is kept as it was")
        val limits = notes.single { it.name == "Limits" }
        assertEquals(math.id, limits.folderId)
        assertEquals(backup.id, notes.single { it.name == "Loose" }.folderId)
        val sourceId = source.documents.first().single { it.name == "Limits" }.id
        assertNotEquals(sourceId, limits.id, "a new note")

        // Its content and its file
        val document = checkNotNull(target.loadDocument(limits.id))
        assertEquals("# Limiti\n\$x \\to 0\$", document.pages.first().texts.single().text)
        val resource = document.resources.single()
        assertContentEquals(byteArrayOf(1, 2, 3, 4), PlatformFiles.readBytes(resource.path))

        // A second import: another folder, the first one as it was
        ArchiveImporter(targetDocuments).import(archive.bytes, "Backup")
        assertTrue(target.folders.first().any { it.name == "Backup (2)" })
        assertEquals(5, target.documents.first().size)
    }

    @Test
    fun anArchiveThatCannotBeReadAddsNothing() = runBlocking {
        val (target, documents) = library()
        val importer = ArchiveImporter(documents)
        assertEquals(ArchiveException.Reason.NOT_AN_ARCHIVE, assertFailsWith<ArchiveException> { importer.import("not a zip".encodeToByteArray(), "X") }.reason)

        // A later version
        val newer = ZipWriter().apply {
            addEntry(ArchiveFormat.MANIFEST, """{"format":"pageboard-archive","version":${ArchiveFormat.VERSION + 1},"documents":[]}""".encodeToByteArray())
        }.toByteArray()
        assertEquals(ArchiveException.Reason.TOO_NEW, assertFailsWith<ArchiveException> { importer.import(newer, "X") }.reason)

        // A note that cannot be read: nothing added, not even the folder
        val broken = ZipWriter().apply {
            addEntry("documents/1/document.json", "{\"id\": 3}".encodeToByteArray())
            addEntry(ArchiveFormat.MANIFEST, """{"format":"pageboard-archive","version":1,"documents":[{"name":"N","folders":[],"entry":"documents/1/document.json","files":{}}]}""".encodeToByteArray())
        }.toByteArray()
        assertEquals(ArchiveException.Reason.NOT_AN_ARCHIVE, assertFailsWith<ArchiveException> { importer.import(broken, "X") }.reason)

        // A damaged entry
        val good = ZipWriter().apply { addEntry(ArchiveFormat.MANIFEST, """{"format":"pageboard-archive","version":1,"documents":[]}""".encodeToByteArray()) }.toByteArray()
        val damaged = good.copyOf().also { it[40] = (it[40] + 1).toByte() }
        assertFailsWith<ArchiveException> { importer.import(damaged, "X") }

        assertTrue(target.documents.first().isEmpty())
        assertTrue(target.folders.first().isEmpty())
    }

    @Test
    fun anArchiveIsSavedWithAGenericTypeSoThatItKeepsItsName() {
        assertEquals("pageboard", ExportFormat.ARCHIVE.extension(3))
        assertEquals("application/octet-stream", ExportFormat.ARCHIVE.mimeType(3))
    }

    @Test
    fun theFolderOfAnImportIsTheNameWithoutTheExtensionsOfAnArchive() {
        assertEquals("slides", ArchiveFormat.folderNameOf("slides.pageboard"))
        assertEquals("slides", ArchiveFormat.folderNameOf("slides.pageboard.zip"))
        assertEquals("slides", ArchiveFormat.folderNameOf("slides.zip"))
        assertEquals("Analysis 1.2", ArchiveFormat.folderNameOf("Analysis 1.2.pageboard"))
        assertEquals("pageboard", ArchiveFormat.folderNameOf(".pageboard"))
    }

    @Test
    fun anArchiveImportedIntoTheLibraryItCameFromLeavesTheNotesThereAsTheyWere() = runBlocking {
        val (store, documents) = filled()
        val limitsId = store.documents.first().single { it.name == "Limits" }.id
        val before = checkNotNull(store.loadDocument(limitsId))
        val pageIds = before.pages.map { it.id }
        val elementIds = before.pages.flatMap { page -> page.texts.map { it.id } + page.images.map { it.id } }
        val resourceIds = before.resources.map { it.id }

        val items = DocumentExport(documents).folderDocuments(null, includeSubfolders = true)
        val archive = ArchiveExporter(documents).write(items)
        ArchiveImporter(documents).import(archive.bytes, "Backup")

        // The note there keeps its pages, its elements and its resource
        val after = checkNotNull(store.loadDocument(limitsId))
        assertEquals(pageIds, after.pages.map { it.id })
        assertEquals(elementIds, after.pages.flatMap { page -> page.texts.map { it.id } + page.images.map { it.id } })
        assertEquals(resourceIds, after.resources.map { it.id })
        assertEquals("# Limiti\n\$x \\to 0\$", after.pages.first().texts.single().text)

        // The copy shares no identifier with it, and its image points to its own resource
        val copyId = store.documents.first().single { it.name == "Limits" && it.id != limitsId }.id
        val copy = checkNotNull(store.loadDocument(copyId))
        val copyIds = copy.pages.map { it.id } + copy.pages.flatMap { page -> page.texts.map { it.id } + page.images.map { it.id } } + copy.resources.map { it.id }
        assertTrue(copyIds.none { it in pageIds || it in elementIds || it in resourceIds }, "new identifiers: $copyIds")
        val picture = copy.pages.first().images.single()
        assertEquals(copy.resources.single().id, picture.resourceId)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), PlatformFiles.readBytes(copy.resources.single().path))
        assertEquals("# Limiti\n\$x \\to 0\$", copy.pages.first().texts.single().text)
    }

    @Test
    fun theIdentifiersOfASnapshotAreRenewedWithEveryReferenceToThem() = runBlocking {
        val (store, _) = filled()
        val limitsId = store.documents.first().single { it.name == "Limits" }.id
        val snapshot = checkNotNull(store.loadDocument(limitsId)).snapshot()
        val files = snapshot.resources.associate { it.id to byteArrayOf(9) }

        val (renewed, renewedFiles) = ArchiveIdentifiers.renew(snapshot, files)
        val old = ArchiveIdentifiers.identifiersOf(snapshot)
        val new = ArchiveIdentifiers.identifiersOf(renewed)
        assertEquals(old.size, new.size)
        assertTrue(new.none { it in old })
        val json = ArchiveFormat.json.encodeToString(DocumentSnapshot.serializer(), renewed)
        assertTrue(old.none { it in json }, "no old identifier left in the snapshot")
        assertEquals(renewed.resources.map { it.id }.toSet(), renewedFiles.keys)
    }
}
