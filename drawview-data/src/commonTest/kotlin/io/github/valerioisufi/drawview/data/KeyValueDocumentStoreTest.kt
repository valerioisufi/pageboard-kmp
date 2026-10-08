package io.github.valerioisufi.drawview.data

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import io.github.valerioisufi.drawview.core.controller.ToolPreset
import io.github.valerioisufi.drawview.core.engine.InkStrokeEngine
import io.github.valerioisufi.drawview.core.geometry.AffineTransform
import io.github.valerioisufi.drawview.core.model.BrushConfig
import io.github.valerioisufi.drawview.core.model.DrawPage
import io.github.valerioisufi.drawview.core.model.DrawStroke
import io.github.valerioisufi.drawview.core.model.DrawText
import io.github.valerioisufi.drawview.core.model.DrawTextMarkup
import io.github.valerioisufi.drawview.core.model.InputToolType
import io.github.valerioisufi.drawview.core.model.PageBackground
import io.github.valerioisufi.drawview.core.model.Stamp
import io.github.valerioisufi.drawview.core.model.StrokePoint
import io.github.valerioisufi.drawview.core.model.StrokeTool
import io.github.valerioisufi.drawview.core.model.TextAlignment
import io.github.valerioisufi.drawview.core.model.TextMarkupKind
import io.github.valerioisufi.drawview.core.model.Tool
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyValueDocumentStoreTest {

    private fun stroke(offset: Float): DrawStroke {
        val points = (0 until 10).map { i ->
            StrokePoint(x = 10f + offset + i * 2f, y = 20f + i, pressure = 0.5f, timestampMs = i * 8L)
        }
        val brush = BrushConfig(tool = StrokeTool.PRESSURE_PEN, size = 0.5f, color = Color(0xFF0000FF))
        val mesh = assertNotNull(InkStrokeEngine.buildMesh(points, brush, InputToolType.STYLUS))
        return DrawStroke(mesh = mesh, brushConfig = brush, transform = AffineTransform(tx = offset))
    }

    @Test
    fun documentsSurviveAReload() = runTest {
        val storage = InMemoryKeyValueStorage()
        val store = KeyValueDocumentStore(storage, scope = backgroundScope)

        val document = store.createDocument("Notes", 210f, 297f, PageBackground.Grid())
        assertEquals(1, store.documents.first().size)
        val page = document.pages.single()

        val strokes = listOf(stroke(0f), stroke(30f))
        page.strokes.addAll(strokes)
        store.saveStrokes(page, strokes)
        val text = DrawText().apply {
            this.text = "**Hello** \$x^2\$"
            x = 5f
            y = 6f
            width = 40f
            height = 12f
            fontSize = 20f
            alignment = TextAlignment.CENTER
        }
        page.texts.add(text)
        store.saveText(page, text)
        val markup = DrawTextMarkup(
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
        page.markups.add(markup)
        store.saveTextMarkup(page, markup)

        val second = DrawPage(index = 1, widthMm = 297f, heightMm = 210f, background = PageBackground.Dotted())
        second.hasCustomBackground = true
        document.pages.add(second)
        store.savePage(document, second)
        store.touchDocument(document, 1234L)
        store.flush()

        // A new store reading the same storage sees the saved content
        val reloaded = KeyValueDocumentStore(storage, scope = backgroundScope)
        val summary = reloaded.documents.first().single()
        assertEquals("Notes", summary.name)
        assertEquals(1234L, summary.modifiedAt)
        assertEquals(PageBackground.Grid(), summary.defaultBackground)

        val loaded = assertNotNull(reloaded.loadDocument(summary.id))
        assertEquals(2, loaded.pages.size)
        val firstPage = loaded.pages[0]
        assertEquals(2, firstPage.strokes.size)
        assertEquals(strokes[0].mesh.points, firstPage.strokes[0].mesh.points)
        assertTrue(firstPage.strokes[0].mesh.positions.isNotEmpty(), "the mesh is rebuilt on load")
        assertEquals(Color(0xFF0000FF), firstPage.strokes[1].brushConfig.color)
        assertEquals(strokes[1].transform, firstPage.strokes[1].transform)
        assertEquals(strokes[1].mesh.points, firstPage.strokes[1].mesh.points)
        assertEquals(PageBackground.Grid(), firstPage.background)
        assertFalse(firstPage.hasCustomBackground)

        val loadedText = firstPage.texts.single()
        assertEquals(text.text, loadedText.text)
        assertEquals(TextAlignment.CENTER, loadedText.alignment)
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
        page.removedElements.add(removed)
        store.saveStrokes(page, listOf(kept, removed))
        val second = DrawPage(index = 1)
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
        val presets = listOf(ToolPreset(1.5f, 0xFF00FF00.toInt(), StrokeTool.PRESSURE_PEN))
        store.savePresets(Tool.INK_PEN, presets)
        store.saveStylusOnlyMode(true)
        store.saveLastSelectedTool(Tool.TEXT)
        store.saveTextDefaults(24f, 0xFFFF0000.toInt())

        val preferences = KeyValueDocumentStore(storage, scope = backgroundScope).preferences.first()
        assertEquals(presets, preferences.presets.getValue(Tool.INK_PEN))
        assertTrue(preferences.isStylusOnlyMode)
        assertEquals(Tool.TEXT, preferences.lastSelectedTool)
        assertEquals(24f, preferences.defaultTextFontSize)
        // Tools without stored presets use the defaults
        assertTrue(preferences.presets.getValue(Tool.INK_HIGHLIGHTER).isNotEmpty())
    }
}
