package io.github.valerioisufi.pageboard.store

import io.github.valerioisufi.pageboard.model.BrushConfig
import io.github.valerioisufi.pageboard.model.Page
import io.github.valerioisufi.pageboard.model.StrokeElement
import io.github.valerioisufi.pageboard.model.TextElement
import io.github.valerioisufi.pageboard.model.TextMarkupElement
import io.github.valerioisufi.pageboard.model.InputToolType
import io.github.valerioisufi.pageboard.model.KotlinStrokeModeller
import io.github.valerioisufi.pageboard.model.PageBackground
import io.github.valerioisufi.pageboard.model.Stamp
import io.github.valerioisufi.pageboard.model.StrokePoint
import io.github.valerioisufi.pageboard.model.StrokeTool
import io.github.valerioisufi.pageboard.model.TextAlignment
import io.github.valerioisufi.pageboard.model.TextMarkupKind
import io.github.valerioisufi.pageboard.model.geometry.AffineTransform
import io.github.valerioisufi.pageboard.model.geometry.Rect
import io.github.valerioisufi.pageboard.store.testing.checkCatalogOfBefore
import io.github.valerioisufi.pageboard.store.testing.checkFolderOperations
import io.github.valerioisufi.pageboard.store.testing.checkInk
import io.github.valerioisufi.pageboard.store.testing.checkOutline
import io.github.valerioisufi.pageboard.store.testing.checkPageFingerprint
import io.github.valerioisufi.pageboard.store.testing.checkTrash
import io.github.valerioisufi.pageboard.store.testing.checkOperationProofs
import io.github.valerioisufi.pageboard.store.testing.checkSharedFolders
import io.github.valerioisufi.pageboard.store.testing.checkSpaceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

class KeyValueDocumentStoreTest {

    private fun stroke(offset: Float): StrokeElement {
        val points = (0 until 10).map { i ->
            StrokePoint(x = 10f + offset + i * 2f, y = 20f + i, pressure = 0.5f, timestampMs = i * 8L)
        }
        val brush = BrushConfig(tool = StrokeTool.PRESSURE_PEN, size = 0.5f, color = 0xFF0000FF.toInt())
        val mesh = assertNotNull(KotlinStrokeModeller.buildMesh(points, brush, InputToolType.STYLUS))
        return StrokeElement(mesh = mesh, brushConfig = brush, transform = AffineTransform(tx = offset))
    }

    @Test
    fun documentsSurviveAReload() = runTest {
        val storage = InMemoryKeyValueStorage()
        val store = KeyValueDocumentStore(storage, scope = backgroundScope)

        val document = store.createDocument("Notes", 210f, 297f, PageBackground.Grid())
        assertEquals(1, store.documents.first().size)
        val page = document.pages.single()

        val strokes = listOf(stroke(0f), stroke(30f))
        strokes.forEach(page::add)
        store.saveStrokes(page, strokes)
        val text = TextElement().apply {
            this.text = "**Hello** \$x^2\$"
            x = 5f
            y = 6f
            width = 40f
            height = 12f
            fontSize = 20f
            alignment = TextAlignment.CENTER
            autoWidth = true
        }
        page.add(text)
        store.saveText(page, text)
        val markup = TextMarkupElement(
            resourceId = "pdf",
            pdfPageIndex = 3,
            pdfWidthPt = 595f,
            pdfHeightPt = 842f,
            startIndex = 4,
            endIndex = 9,
            text = "words",
            rects = listOf(Rect(72f, 100f, 132f, 124f)),
            kind = TextMarkupKind.STRIKEOUT,
            color = 0xFF000000.toInt()
        )
        page.add(markup)
        store.saveTextMarkup(page, markup)

        val second = Page(index = 1, widthMm = 297f, heightMm = 210f, background = PageBackground.Dotted())
        second.hasCustomBackground = true
        document.appendPage(second)
        store.savePage(document, second)
        store.touchDocument(document, 1234L)
        store.flush()

        // A new store reading the same storage sees the saved content
        val reloaded = KeyValueDocumentStore(storage, scope = backgroundScope)
        val summary = reloaded.documents.first().single()
        assertEquals("Notes", summary.name)
        assertEquals(1234L, summary.modifiedAt)
        assertEquals(PageBackground.Grid(), summary.defaultBackground)
        // What the library tells of the content: the pages, no PDF placed (a mark only)
        assertEquals(2, summary.pageCount)
        assertFalse(summary.hasPdf)

        val loaded = assertNotNull(reloaded.loadDocument(summary.id))
        assertEquals(2, loaded.pages.size)
        val firstPage = loaded.pages[0]
        assertEquals(2, firstPage.strokes.size)
        assertEquals(strokes[0].mesh.points, firstPage.strokes[0].mesh.points)
        assertTrue(firstPage.strokes[0].mesh.positions.isNotEmpty(), "the mesh is rebuilt on load")
        assertEquals(0xFF0000FF.toInt(), firstPage.strokes[1].brushConfig.color)
        assertEquals(strokes[1].transform, firstPage.strokes[1].transform)
        assertEquals(strokes[1].mesh.points, firstPage.strokes[1].mesh.points)
        assertEquals(PageBackground.Grid(), firstPage.background)
        assertFalse(firstPage.hasCustomBackground)

        val loadedText = firstPage.texts.single()
        assertEquals(text.text, loadedText.text)
        assertEquals(TextAlignment.CENTER, loadedText.alignment)
        assertTrue(loadedText.autoWidth)
        assertEquals(20f, loadedText.fontSize)

        val loadedMarkup = firstPage.markups.single()
        assertEquals("words", loadedMarkup.text)
        assertEquals(3, loadedMarkup.pdfPageIndex)
        assertEquals(4 until 9, loadedMarkup.startIndex until loadedMarkup.endIndex)
        assertEquals(markup.rects, loadedMarkup.rects)
        assertEquals(TextMarkupKind.STRIKEOUT, loadedMarkup.kind)

        // The identifiers on every device are stored
        assertEquals(document.id, loaded.id)
        assertEquals(document.id, summary.id)
        assertEquals(listOf(page.id, second.id), loaded.pages.map { it.id })
        assertEquals(strokes.map { it.id }, firstPage.strokes.map { it.id })
        assertEquals(text.id, loadedText.id)
        assertEquals(markup.id, loadedMarkup.id)

        assertEquals(297f, loaded.pages[1].widthMm)
        assertTrue(loaded.pages[1].hasCustomBackground)
        assertEquals(PageBackground.Dotted(), loaded.pages[1].background)
    }

    @Test
    fun removedPagesAndElementsSurviveAReload() = runTest {
        val storage = InMemoryKeyValueStorage()
        val store = KeyValueDocumentStore(storage, scope = backgroundScope)
        val document = store.createDocument("Notes", 210f, 297f, PageBackground.Solid())
        val page = document.pages.single()
        val (kept, removed) = listOf(stroke(0f), stroke(30f))
        page.add(kept)
        removed.removed = Stamp(2L, 0, "device")
        removed.placed = Stamp(3L, 0, "device")
        page.load(emptyList(), listOf(removed))
        store.saveStrokes(page, listOf(kept, removed))
        val second = Page(index = 1)
        second.removed = Stamp(4L, 0, "device")
        second.add(stroke(60f))
        document.removedPages[second.id] = second
        store.savePage(document, second)
        store.flush()

        val loaded = assertNotNull(KeyValueDocumentStore(storage, scope = backgroundScope).loadDocument(document.id))
        val loadedPage = loaded.pages.single()
        assertEquals(listOf(kept.id), loadedPage.strokes.map { it.id })
        val hidden = loadedPage.removedElements.single()
        assertEquals(removed.id, hidden.id)
        assertEquals(removed.removed, hidden.removed)
        assertEquals(removed.placed, hidden.placed)
        val hiddenPage = assertNotNull(loaded.removedPages[second.id])
        assertEquals(second.removed, hiddenPage.removed)
        assertEquals(1, hiddenPage.strokes.size)
    }

    @Test
    fun aDocumentStoredBeforeTheOneStackKeepsItsLook() = runTest {
        val storage = InMemoryKeyValueStorage()
        val store = KeyValueDocumentStore(storage, scope = backgroundScope)
        val document = store.createDocument("Notes", 210f, 297f, PageBackground.Solid())
        store.flush()
        // The document as the versions before the one stack wrote it: its orders `stamp/index`
        // mixing the kinds (the newest an image, the oldest a stroke), no register of the order
        fun stamp(time: Long) = Stamp(time, 0, "ada").toString()
        fun order(time: Long) = "${stamp(time)}/00000000"
        fun versions(time: Long) = """{"added":"${stamp(time)}","placed":"${stamp(time + 1)}","updated":"${stamp(time)}"}"""
        storage.set(
            "pageboard.document.${document.id}",
            """
            {"pages":[{"data":{"id":"page","orderKey":"V","widthMm":210.0,"heightMm":297.0,"elements":[
              {"type":"image","id":"image","resourceId":"r","x":0.0,"y":0.0,"width":20.0,"height":20.0,"order":"${order(300)}"},
              {"type":"stroke","id":"stroke","points":[{"x":1.0,"y":1.0},{"x":5.0,"y":5.0}],"tool":"PRESSURE_PEN","size":0.5,"color":-16777216,"order":"${order(100)}"},
              {"type":"text","id":"text","text":"waves","x":0.0,"y":0.0,"width":20.0,"height":10.0,"color":-16777216,"fontSize":16.0,"order":"${order(200)}"}
            ]},"versions":{"image":${versions(300)},"stroke":${versions(100)},"text":${versions(200)}}}]}
            """.trimIndent()
        )

        val reloaded = KeyValueDocumentStore(storage, scope = backgroundScope)
        val page = assertNotNull(reloaded.loadDocument(document.id)).pages.single()
        assertEquals(listOf("image", "text", "stroke"), page.stack.map { it.id })
        for (element in page.elements()) assertEquals(element.placed, element.ordered, element.id)
    }

    @Test
    fun aDocumentOpenInAnEditorIsRenamedInItsSession() = runTest {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = backgroundScope)
        val a = store.createDocument("A")
        store.closeDocument(a.id)
        val documents = OpenDocuments(store, StandardTestDispatcher(testScheduler), backgroundScope)
        // The editor keeps the document open: its title renames the session it shows
        val editor = assertNotNull(documents.open(a.id))

        assertTrue(documents.rename(a.id, "Physics"))
        assertEquals("Physics", editor.document.title)
        assertEquals(listOf("Physics"), store.documents.first().map { it.name })
        documents.close(editor)
    }

    @Test
    fun renameAndDelete() = runTest {
        val storage = InMemoryKeyValueStorage()
        val store = KeyValueDocumentStore(storage, scope = backgroundScope)
        val a = store.createDocument("A")
        val b = store.createDocument("B")

        val documents = OpenDocuments(store, StandardTestDispatcher(testScheduler), backgroundScope)
        assertFalse(documents.rename(a.id, "B"), "names are unique")
        assertTrue(documents.rename(a.id, "C"))
        assertEquals(setOf("B", "C"), store.documents.first().map { it.name }.toSet())
        // By a change: the stamp of the title is kept with it
        assertEquals("C", assertNotNull(store.loadDocument(a.id)).title)
        assertTrue(assertNotNull(store.loadDocument(a.id)).titleVersion > Stamp.ZERO)

        assertTrue(store.deleteDocument(b.id))
        assertNull(store.loadDocument(b.id))
        assertEquals(listOf("C"), store.documents.first().map { it.name })
        assertTrue(storage.keys.none { it.endsWith("document.${b.id}") })
    }

    @Test
    fun foldersAreSupported() = runTest {
        val storage = InMemoryKeyValueStorage()
        checkFolderOperations(KeyValueDocumentStore(storage, scope = backgroundScope))
    }

    @Test
    fun theTrashIsKept() = runTest {
        checkTrash(KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = backgroundScope))
    }

    @Test
    fun theOutlineIsTheDocumentWithoutItsStrokes() = runTest {
        checkOutline(KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = backgroundScope))
    }

    @Test
    fun theInkIsReadWithoutModelingTheStrokes() = runTest {
        checkInk(KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = backgroundScope))
    }

    @Test
    fun theFingerprintOfAPageChangesWithIt() = runTest {
        checkPageFingerprint(KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = backgroundScope))
    }

    @Test
    fun theTrashSurvivesAReload() = runTest {
        val storage = InMemoryKeyValueStorage()
        val store = KeyValueDocumentStore(storage, scope = backgroundScope, clock = { 1_000L })
        val notes = store.createDocument("Notes")
        assertTrue(store.trashDocument(notes.id))
        store.flush()

        val reloaded = KeyValueDocumentStore(storage, scope = backgroundScope)
        assertTrue(reloaded.documents.first().isEmpty())
        assertEquals(1_000L, reloaded.trash.first().single().trashedAt)
        assertEquals("Notes", reloaded.restore(notes.id))
    }

    @Test
    fun anIndexStoredBeforeTheTrashIsReadAsItWas() = runTest {
        val storage = InMemoryKeyValueStorage()
        storage.set(
            "pageboard.index",
            """{"replicaId":"a","documents":[{"id":"d","name":"Lectures","folderId":"f","createdAt":1,"modifiedAt":2,"lastOpenedAt":3}],""" +
                """"folders":[{"id":"f","name":"Physics","createdAt":1,"modifiedAt":2}]}"""
        )
        val store = KeyValueDocumentStore(storage, scope = backgroundScope)
        assertEquals(listOf("Lectures"), store.documents.first().map { it.name })
        assertEquals(listOf("Physics"), store.folders.first().map { it.name })
        assertTrue(store.trash.first().isEmpty())
    }

    @Test
    fun theProofsOfTheOperationsAreKept() = runTest {
        val storage = InMemoryKeyValueStorage()
        checkOperationProofs(KeyValueDocumentStore(storage, scope = backgroundScope)) { KeyValueDocumentStore(storage, scope = backgroundScope) }
    }

    @Test
    fun theSpacesAreKept() = runTest {
        val storage = InMemoryKeyValueStorage()
        checkSpaceStore(KeyValueDocumentStore(storage, scope = backgroundScope)) { KeyValueDocumentStore(storage, scope = backgroundScope) }
    }

    @Test
    fun aCatalogOfBeforeIsCompletedFromItsLog() = runTest {
        val storage = InMemoryKeyValueStorage()
        checkCatalogOfBefore(KeyValueDocumentStore(storage, scope = backgroundScope), backgroundScope) { KeyValueDocumentStore(storage, scope = backgroundScope) }
    }

    @Test
    fun aFolderIsSharedBetweenTwoDevices() = runTest {
        checkSharedFolders(
            KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = backgroundScope),
            KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = backgroundScope),
            backgroundScope
        )
    }

    @Test
    fun aStorageOfBeforeHasNoSpaces() = runTest {
        val storage = InMemoryKeyValueStorage()
        val before = KeyValueDocumentStore(storage, scope = backgroundScope)
        assertTrue(before.createFolder("Course", null))
        before.createDocument("Notes")
        before.flush()
        // A storage written before the spaces: no key of theirs
        assertNull(storage.get("pageboard.spaces"))
        val store = KeyValueDocumentStore(storage, scope = backgroundScope)
        assertEquals(emptyList(), store.spaces())
        assertEquals(1, store.documents.first().size)
        assertEquals(1, store.folders.first().size)
    }

    @Test
    fun foldersSurviveAReload() = runTest {
        val storage = InMemoryKeyValueStorage()
        val store = KeyValueDocumentStore(storage, scope = backgroundScope)
        assertTrue(store.createFolder("Math", null))
        val folder = store.folders.first().single()
        store.createDocument("Notes", folderId = folder.id)

        val reloaded = KeyValueDocumentStore(storage, scope = backgroundScope)
        assertEquals(listOf("Math"), reloaded.folders.first().map { it.name })
        assertEquals(folder.id, reloaded.documents.first().single().folderId)
        // Identifiers are not reused after a reload
        assertTrue(reloaded.createFolder("Physics", null))
        assertTrue(reloaded.folders.first().single { it.name == "Physics" }.id != folder.id)
    }

    @Test
    fun preferencesArePersisted() = runTest {
        val storage = InMemoryKeyValueStorage()
        val store = KeyValueDocumentStore(storage, scope = backgroundScope)
        store.setPreference("pageboard.editor", "{\"isStylusOnlyMode\":true}")
        store.setPreference("other", "1")
        store.setPreference("other", null)

        val reloaded = KeyValueDocumentStore(storage, scope = backgroundScope)
        assertEquals("{\"isStylusOnlyMode\":true}", reloaded.preference("pageboard.editor"))
        assertNull(reloaded.preference("other"))
    }
}
