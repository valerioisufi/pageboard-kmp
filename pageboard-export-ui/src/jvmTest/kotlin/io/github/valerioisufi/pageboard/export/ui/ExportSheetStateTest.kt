package io.github.valerioisufi.pageboard.export.ui

import io.github.valerioisufi.pageboard.export.ExportContents
import io.github.valerioisufi.pageboard.export.ExportFormat
import io.github.valerioisufi.pageboard.export.ExportScope
import io.github.valerioisufi.pageboard.export.FolderDocumentItem
import io.github.valerioisufi.pageboard.export.RecordingNamePart
import io.github.valerioisufi.pageboard.store.DocumentSummary
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

    @Test
    fun recordingsFormatIsOfferedOnlyWhenSupportedAndPersistsTranscriptChoice() {
        val withoutRecordings = ExportSheetState(ExportScope.Pages("lecture"), ExportPreferences(), hasRecordings = false)
        assertEquals(
            listOf(ExportFormat.PDF, ExportFormat.IMAGES, ExportFormat.MARKDOWN, ExportFormat.ARCHIVE),
            withoutRecordings.formats
        )

        val preferences = ExportPreferences()
        val singleWithRecordings = ExportSheetState(
            ExportScope.Pages("lecture"),
            preferences,
            hasRecordings = true
        ).apply {
            contents = ExportContents(scope, "Lecture 1", listOf(FolderDocumentItem(summary("Lecture 1", 4), "Lecture 1.pdf")), 4)
        }
        assertEquals(
            listOf(ExportFormat.PDF, ExportFormat.IMAGES, ExportFormat.MARKDOWN, ExportFormat.ARCHIVE, ExportFormat.RECORDINGS),
            singleWithRecordings.formats
        )

        // Invalid page range does not block RECORDINGS export
        singleWithRecordings.pages = PageChoice.RANGE
        singleWithRecordings.range = "bad"
        assertEquals(ExportProblem.INVALID_RANGE, singleWithRecordings.problem)
        singleWithRecordings.format = ExportFormat.RECORDINGS
        assertNull(singleWithRecordings.problem)
        assertEquals("Lecture 1.zip", singleWithRecordings.fileName("Notes"))
        assertEquals("application/zip", singleWithRecordings.mimeType)

        singleWithRecordings.includeTranscripts = true
        singleWithRecordings.remember()
        assertEquals(true, singleWithRecordings.options().includeTranscripts)

        val folderWithRecordings = ExportSheetState(ExportScope.Folder(null), preferences, hasRecordings = true)
        assertEquals(
            listOf(ExportFormat.PDF_PER_DOCUMENT, ExportFormat.COMBINED_PDF, ExportFormat.ARCHIVE, ExportFormat.RECORDINGS),
            folderWithRecordings.formats
        )
        assertEquals(true, folderWithRecordings.includeTranscripts)

        // Saved RECORDINGS choice falls back to the default format when recordings are unavailable
        val fallbackSingle = ExportSheetState(ExportScope.Pages("lecture"), preferences, hasRecordings = false)
        assertEquals(ExportFormat.PDF, fallbackSingle.format)

        // Custom recording name parts can be toggled, reordered, and persisted (never empty)
        val naming = ExportSheetState(ExportScope.Folder(null), preferences, hasRecordings = true)
        assertEquals(listOf(RecordingNamePart.NOTE, RecordingNamePart.DATE), naming.recordingNameParts)
        naming.toggleRecordingNamePart(RecordingNamePart.TOPIC)
        naming.toggleRecordingNamePart(RecordingNamePart.FOLDER)
        assertEquals(
            listOf(RecordingNamePart.NOTE, RecordingNamePart.DATE, RecordingNamePart.TOPIC, RecordingNamePart.FOLDER),
            naming.recordingNameParts
        )
        naming.moveRecordingNamePart(2, -1) // TOPIC moves before DATE
        naming.moveRecordingNamePart(0, -1) // Out of bounds no-op
        naming.moveRecordingNamePart(3, +1) // Out of bounds no-op
        assertEquals(
            listOf(RecordingNamePart.NOTE, RecordingNamePart.TOPIC, RecordingNamePart.DATE, RecordingNamePart.FOLDER),
            naming.recordingNameParts
        )
        naming.toggleRecordingNamePart(RecordingNamePart.NOTE)
        naming.toggleRecordingNamePart(RecordingNamePart.DATE)
        naming.toggleRecordingNamePart(RecordingNamePart.FOLDER)
        assertEquals(listOf(RecordingNamePart.TOPIC), naming.recordingNameParts)
        // Cannot remove the last remaining field
        naming.toggleRecordingNamePart(RecordingNamePart.TOPIC)
        assertEquals(listOf(RecordingNamePart.TOPIC), naming.recordingNameParts)

        naming.toggleRecordingNamePart(RecordingNamePart.DATE)
        naming.remember()
        assertEquals(listOf(RecordingNamePart.TOPIC, RecordingNamePart.DATE), naming.options().recordingNameParts)

        val restoredNaming = ExportSheetState(ExportScope.Folder(null), preferences, hasRecordings = true)
        assertEquals(listOf(RecordingNamePart.TOPIC, RecordingNamePart.DATE), restoredNaming.recordingNameParts)
    }
}
