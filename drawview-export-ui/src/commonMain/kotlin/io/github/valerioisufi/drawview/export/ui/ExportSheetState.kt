package io.github.valerioisufi.drawview.export.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.valerioisufi.drawview.export.DocumentExport
import io.github.valerioisufi.drawview.export.ExportContents
import io.github.valerioisufi.drawview.export.ExportFormat
import io.github.valerioisufi.drawview.export.ExportOptions
import io.github.valerioisufi.drawview.export.ExportScope

/** The pages of a note the user exports. */
internal enum class PageChoice { ALL, ON_SCREEN, SELECTED, RANGE }

/** Why the export cannot start yet, told in place of the button doing it. */
internal enum class ExportProblem { LOADING, NO_NOTES, NOTE_MISSING, INVALID_RANGE, NO_NAME }

/**
 * The choices remembered from one export to the next while the application runs (see
 * `docs/export-ux.md`): the format of a note and of several, the pattern of the backgrounds, the
 * subfolders.
 */
internal class ExportPreferences {
    var singleFormat: ExportFormat = ExportFormat.PDF
    var multipleFormat: ExportFormat = ExportFormat.PDF_PER_DOCUMENT
    var backgroundPattern: Boolean = true
    var includeSubfolders: Boolean = true
}

/**
 * The page looked at in the editor, [id] at [index] (from 0), offered to be exported alone.
 */
internal class PageOnScreen(val id: String, val index: Int)

/**
 * The choices of the export sheet and their rules (the Pure Fabrication behind `ExportSheet`):
 * the formats offered for [scope], the pages of a note (all, [pageOnScreen], [selectedPages], a
 * range), the options, the name of the file, and whether the export can start.
 *
 * @param preferences the choices of the last export, offered again and kept by [remember].
 */
@Stable
internal class ExportSheetState(
    val scope: ExportScope,
    private val preferences: ExportPreferences,
    val pageOnScreen: PageOnScreen? = null,
    val selectedPages: Set<String> = emptySet()
) {
    /** What the scope gives, `null` while it is being read (again when the subfolders change). */
    var contents: ExportContents? by mutableStateOf(null)

    var format: ExportFormat by mutableStateOf(
        if (scope is ExportScope.Pages) preferences.singleFormat else preferences.multipleFormat
    )

    var pages: PageChoice by mutableStateOf(if (selectedPages.isNotEmpty()) PageChoice.SELECTED else PageChoice.ALL)

    /** The range of pages written by the user, e.g. "1-3, 5". */
    var range: String by mutableStateOf("")

    var backgroundPattern: Boolean by mutableStateOf(preferences.backgroundPattern)

    var includeSubfolders: Boolean by mutableStateOf(preferences.includeSubfolders)

    /** The name of the file without its extension, as edited by the user; `null` while untouched. */
    var editedName: String? by mutableStateOf(null)

    /** Whether the pages of one note are exported (they can be chosen). */
    val isSingleDocument: Boolean get() = scope is ExportScope.Pages

    /** The formats offered. */
    val formats: List<ExportFormat>
        get() = if (isSingleDocument) listOf(ExportFormat.PDF, ExportFormat.IMAGES) else listOf(ExportFormat.PDF_PER_DOCUMENT, ExportFormat.COMBINED_PDF)

    /** The choices of pages offered for a note: all, the page on screen, the pages selected, a range. */
    val pageChoices: List<PageChoice>
        get() = buildList {
            add(PageChoice.ALL)
            if (pageOnScreen != null) add(PageChoice.ON_SCREEN)
            if (selectedPages.isNotEmpty()) add(PageChoice.SELECTED)
            if ((contents?.pages ?: 0) > 1) add(PageChoice.RANGE)
        }

    /** The pages of the range (from 0), `null` when it is not a valid range of the note. */
    val rangeIndices: Set<Int>? get() = parseRange(range, contents?.pages ?: 0)

    /** How many pages are exported (of all the notes when there are several). */
    val exportedPages: Int
        get() = when (pages.takeIf { isSingleDocument }) {
            PageChoice.ON_SCREEN -> 1
            PageChoice.SELECTED -> selectedPages.size
            PageChoice.RANGE -> rangeIndices?.size ?: 0
            else -> contents?.pages ?: 0
        }

    /** Why the export cannot start, `null` when it can. */
    val problem: ExportProblem?
        get() {
            val contents = contents ?: return ExportProblem.LOADING
            return when {
                isSingleDocument && contents.documents.isEmpty() -> ExportProblem.NOTE_MISSING
                contents.documents.isEmpty() -> ExportProblem.NO_NOTES
                isSingleDocument && pages == PageChoice.RANGE && rangeIndices == null -> ExportProblem.INVALID_RANGE
                editedName?.isBlank() == true -> ExportProblem.NO_NAME
                else -> null
            }
        }

    /** The extension of the file, without the dot. */
    val extension: String get() = format.extension(exportedPages)

    /** The media type of the file. */
    val mimeType: String get() = format.mimeType(exportedPages)

    /** The name of the file without its extension: the one edited, else the name of what is exported. */
    fun baseName(fallbackName: String): String =
        editedName ?: (contents?.fileName(format, exportedPages, fallbackName)?.removeSuffix(".$extension") ?: "")

    /** The name of the file written. */
    fun fileName(fallbackName: String): String = "${DocumentExport.sanitizeFileName(baseName(fallbackName))}.$extension"

    /** What is exported, with the pages chosen. */
    fun exportedContents(): ExportContents {
        val contents = checkNotNull(contents)
        val scope = scope as? ExportScope.Pages ?: return contents
        val pageIds = when (pages) {
            PageChoice.ON_SCREEN -> pageOnScreen?.let { setOf(it.id) }
            PageChoice.SELECTED -> selectedPages
            else -> scope.pageIds
        }
        return ExportContents(ExportScope.Pages(scope.documentId, pageIds), contents.name, contents.documents, exportedPages)
    }

    /** The options of the export chosen. */
    fun options(): ExportOptions = ExportOptions(
        backgroundPattern = backgroundPattern,
        pageIndices = if (isSingleDocument && pages == PageChoice.RANGE) rangeIndices else null
    )

    /** Keeps the choices of this export for the next one. */
    fun remember() {
        if (isSingleDocument) preferences.singleFormat = format else preferences.multipleFormat = format
        preferences.backgroundPattern = backgroundPattern
        if (!isSingleDocument) preferences.includeSubfolders = includeSubfolders
    }

    companion object {
        /**
         * The pages (from 0) of [text], pages from 1 to [pageCount] and ranges separated by commas
         * (e.g. "1-3, 5", the dashes "–" and "—" too), or `null` when it is empty or names a page
         * the note does not have.
         */
        fun parseRange(text: String, pageCount: Int): Set<Int>? {
            val parts = text.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            val pages = mutableSetOf<Int>()
            for (part in parts) {
                val bounds = part.split('-', '–', '—').map { it.trim() }
                val first = bounds.first().toIntOrNull() ?: return null
                val last = when (bounds.size) {
                    1 -> first
                    2 -> bounds[1].toIntOrNull() ?: return null
                    else -> return null
                }
                if (first < 1 || last > pageCount || first > last) return null
                for (page in first..last) pages += page - 1
            }
            return pages.sorted().toSet()
        }
    }
}
