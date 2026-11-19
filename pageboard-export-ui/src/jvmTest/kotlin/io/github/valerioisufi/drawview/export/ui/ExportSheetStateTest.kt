package io.github.valerioisufi.drawview.export.ui

import io.github.valerioisufi.drawview.export.ExportContents
import io.github.valerioisufi.drawview.export.ExportFormat
import io.github.valerioisufi.drawview.export.ExportScope
import io.github.valerioisufi.drawview.export.FolderDocumentItem
import io.github.valerioisufi.drawview.store.DocumentSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExportSheetStateTest {
    private fun summary(name: String, pages: Int) = DocumentSummary(id = name, name = name, createdAt = 0, modifiedAt = 0, lastOpenedAt = 0, pageCount = pages)

    private fun note(pages: Int = 12, onScreen: PageOnScreen? = null, selected: Set<String> = emptySet(), preferences: ExportPreferences = ExportPreferences()) =
        ExportSheetState(ExportScope.Pages("lecture"), preferences, onScreen, selected).apply {
            contents = ExportContents(scope, "Lecture 1", listOf(FolderDocumentItem(summary("Lecture 1", pages), "Lecture 1.pdf")), pages)
        }

    @Test
    fun aRangeOfPagesIsReadAsWritten() {
        assertEquals(setOf(0, 1, 2, 4), ExportSheetState.parseRange("1-3, 5", 12))
        assertEquals(setOf(6, 7), ExportSheetState.parseRange(" 7 – 8 ", 12))
        assertNull(ExportSheetState.parseRange("", 12))
        assertNull(ExportSheetState.parseRange("0-2", 12))
        assertNull(ExportSheetState.parseRange("5-13", 12))
        assertNull(ExportSheetState.parseRange("3-1", 12))
        assertNull(ExportSheetState.parseRange("a", 12))
    }

    @Test
    fun thePagesOfferedFollowWhereTheExportIsAsked() {
        assertEquals(listOf(PageChoice.ALL, PageChoice.RANGE), note().pageChoices)
        assertEquals(listOf(PageChoice.ALL), note(pages = 1).pageChoices)
        val editor = note(onScreen = PageOnScreen("p3", 2), selected = setOf("p1", "p2"))
        assertEquals(listOf(PageChoice.ALL, PageChoice.ON_SCREEN, PageChoice.SELECTED, PageChoice.RANGE), editor.pageChoices)
        // The pages selected in the grid are the choice when there are some
        assertEquals(PageChoice.SELECTED, editor.pages)
        assertEquals(setOf("p1", "p2"), (editor.exportedContents().scope as ExportScope.Pages).pageIds)
    }

    @Test
    fun theFileFollowsTheFormatAndThePages() {
        val state = note()
        assertEquals("Lecture 1.pdf", state.fileName("Notes"))
        state.format = ExportFormat.IMAGES
        assertEquals("Lecture 1.zip", state.fileName("Notes"))
        state.pages = PageChoice.RANGE
        state.range = "4"
        assertEquals("Lecture 1.png", state.fileName("Notes"))
        assertEquals("image/png", state.mimeType)
        assertEquals(setOf(3), state.options().pageIndices)
        state.editedName = "Limits: summary"
        assertEquals("Limits_ summary.png", state.fileName("Notes"))
    }

    @Test
    fun whatStopsTheExportIsTold() {
        val state = note()
        assertNull(state.problem)
        state.pages = PageChoice.RANGE
        assertEquals(ExportProblem.INVALID_RANGE, state.problem)
        state.range = "2-4"
        assertNull(state.problem)
        state.editedName = " "
        assertEquals(ExportProblem.NO_NAME, state.problem)

        val folder = ExportSheetState(ExportScope.Folder("empty"), ExportPreferences())
        assertEquals(ExportProblem.LOADING, folder.problem)
        folder.contents = ExportContents(folder.scope, "Empty", emptyList(), 0)
        assertEquals(ExportProblem.NO_NOTES, folder.problem)
        assertEquals("Empty.zip", folder.fileName("Home"))
    }

    @Test
    fun theChoicesAreOfferedAgain() {
        val preferences = ExportPreferences()
        note(preferences = preferences).apply {
            format = ExportFormat.IMAGES
            backgroundPattern = false
            remember()
        }
        val next = note(preferences = preferences)
        assertEquals(ExportFormat.IMAGES, next.format)
        assertEquals(false, next.backgroundPattern)
        assertEquals(ExportFormat.PDF_PER_DOCUMENT, ExportSheetState(ExportScope.Folder(null), preferences).format)
    }
}
