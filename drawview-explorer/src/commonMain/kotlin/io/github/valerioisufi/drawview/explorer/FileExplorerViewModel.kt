package io.github.valerioisufi.drawview.explorer

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.valerioisufi.drawview.explorer.generated.resources.Res
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_error_create_failed
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.store.DocumentStore
import io.github.valerioisufi.drawview.store.DocumentSummary
import io.github.valerioisufi.drawview.store.FolderSummary
import io.github.valerioisufi.drawview.store.OpenDocuments
import io.github.valerioisufi.drawview.store.TrashedItem
import io.github.valerioisufi.drawview.ui.extension.DocumentTag
import io.github.valerioisufi.drawview.ui.generated.resources.Res as DrawViewRes
import io.github.valerioisufi.drawview.ui.generated.resources.name_error_exists
import io.github.valerioisufi.drawview.ui.screen.DocumentNames
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

/**
 * State of the document library: navigation between the folders, sorting, selection and the
 * operations on documents and folders. The content follows the changes of the store of
 * [openDocuments]; the documents are renamed by a change of them (the devices they are shared with
 * rename them too).
 */
internal class FileExplorerViewModel(private val openDocuments: OpenDocuments) : ViewModel() {
    private val store: DocumentStore get() = openDocuments.store

    enum class FileType { FILE, FOLDER }

    /**
     * A document or a folder of the library.
     *
     * @param folderId the folder holding it, `null` in the root.
     * @param isShared a document shared with other devices.
     * @param pageCount the pages of a document.
     * @param hasPdf a document one of whose pages holds a PDF.
     * @param noteCount the documents of a folder, at any depth.
     */
    data class FileItem(
        val type: FileType,
        val id: String,
        val name: String,
        val createdAt: Long,
        val modifiedAt: Long,
        val lastOpenedAt: Long? = null,
        val isShared: Boolean = false,
        val folderId: String? = null,
        val pageCount: Int = 0,
        val hasPdf: Boolean = false,
        val noteCount: Int = 0
    ) {
        /** Identity of the item, stable across renames and moves. */
        val key: String get() = "${type.name}:$id"
    }

    enum class SortOption {
        NAME, DATE_CREATED, DATE_MODIFIED, LAST_OPENED
    }

    /** What the library shows: the folders from the root, the notes opened last, the shared ones, the trash. */
    enum class Section { HOME, RECENT, SHARED, TRASH }

    /** What was moved to the trash at once: the [names] and the [ids] of the items, told with Undo. */
    class TrashNotice(val names: List<String>, val ids: List<String>)

    /** How the notes are shown where there is room for both: cards with their first page, or rows. */
    enum class ViewMode { GRID, LIST }

    /**
     * Where a search looks: [HERE] is what the library shows (the current folder with its
     * sub-folders, the notes of a section), [LIBRARY] everything in it.
     */
    enum class SearchScope { HERE, LIBRARY }

    private var documents by mutableStateOf(emptyList<DocumentSummary>())
    private var folders by mutableStateOf(emptyList<FolderSummary>())

    /** What is in the trash, the most recent first (see [Section.TRASH]). */
    var trash by mutableStateOf(emptyList<TrashedItem>())
        private set

    /** What was moved to the trash last, to tell with Undo (`null` once told). */
    var trashNotice by mutableStateOf<TrashNotice?>(null)
        private set

    var sortOption by mutableStateOf(SortOption.NAME)
        private set
    var sortAscending by mutableStateOf(true)
        private set
    var keepFoldersOnTop by mutableStateOf(true)
        private set

    /** Identifiers of the folders from the root to the current one. */
    private val directoryIdSequence = mutableStateListOf<String>()

    private val currentFolderId: String? get() = directoryIdSequence.lastOrNull()

    /** The current folder, `null` in the root. */
    val currentFolder: FolderSummary? by derivedStateOf {
        directoryIdSequence.lastOrNull()?.let { id -> folders.firstOrNull { it.id == id } }
    }

    /** The documents of each folder, at any depth. */
    private val noteCounts: Map<String, Int> by derivedStateOf {
        val parents = folders.associate { it.id to it.parentId }
        val counts = mutableMapOf<String, Int>()
        for (document in documents) {
            var folder = document.folderId
            while (folder != null) {
                counts[folder] = (counts[folder] ?: 0) + 1
                folder = parents[folder]
            }
        }
        counts
    }

    private fun FolderSummary.toFileItem() = FileItem(
        type = FileType.FOLDER,
        id = id,
        name = name,
        createdAt = createdAt,
        modifiedAt = modifiedAt,
        folderId = parentId,
        noteCount = noteCounts[id] ?: 0
    )

    /** Content of the current folder, in the chosen order. */
    val currentDirectoryFiles: List<FileItem> by derivedStateOf {
        val folderId = directoryIdSequence.lastOrNull()
        val items = folders.filter { it.parentId == folderId }.map { it.toFileItem() }.sortedBy { it.name } +
            documents.filter { it.folderId == folderId }.map { it.toFileItem() }.sortedBy { it.name }
        sortFiles(items, sortOption, sortAscending, keepFoldersOnTop)
    }

    /** The folders of the root, by name (the navigation pane of a wide library). */
    val rootFolders: List<FileItem> by derivedStateOf {
        folders.filter { it.parentId == null }.sortedBy { it.name }.map { it.toFileItem() }
    }

    /** The section shown (the folders from the root, the recent notes, the shared ones). */
    var section by mutableStateOf(Section.HOME)
        private set

    /** The text searched in the names and the contents of the notes and in the names of the folders. */
    var searchQuery by mutableStateOf("")
        private set

    /** Where the search looks; it starts over from [SearchScope.HERE] each time. */
    var searchScope by mutableStateOf(SearchScope.HERE)
        private set

    /** Whether the library shows the results of a search in place of what it holds. */
    val isSearching: Boolean get() = searchQuery.isNotBlank()

    /** Whether a search can look further than it does by default: not from the root, nor in the trash. */
    val canWidenSearch: Boolean
        get() = isSearching && section != Section.TRASH && (section != Section.HOME || currentFolderId != null)

    /** The notes shown: all of them, or those of a kind (see [LibraryFilter]). */
    var filter by mutableStateOf<LibraryFilter>(LibraryFilter.All)
        private set

    /** How the notes are shown where there is room for both (cards or rows). */
    var viewMode by mutableStateOf(ViewMode.GRID)

    /** What the application says of the documents (e.g. that they have a lecture), besides the built-in tags. */
    var tags by mutableStateOf(emptyList<DocumentTag>())

    /** Search of the contents of the notes (`drawview-search-ui`), `null` when only names are searched. */
    var contentSearch by mutableStateOf<LibrarySearch?>(null)

    private val pdfDocuments: Set<String> by derivedStateOf { documents.filter { it.hasPdf }.mapTo(mutableSetOf()) { it.id } }
    private val sharedDocuments: Set<String> by derivedStateOf { documents.filter { it.isShared }.mapTo(mutableSetOf()) { it.id } }
    private val builtInTags = pdfTag { pdfDocuments } to sharedTag { sharedDocuments }

    /** The tags of the library: the PDFs, those of the application, the shared documents. */
    val allTags: List<DocumentTag> by derivedStateOf { listOf(builtInTags.first) + tags + listOf(builtInTags.second) }

    /** The tags of the document [item], in the order of [allTags]. */
    fun tagsOf(item: FileItem): List<DocumentTag> =
        if (item.type == FileType.FOLDER) emptyList() else allTags.filter { it.contains(item.id) }

    /** Matching passages of the document [item] for the active [searchQuery]. */
    fun searchMatchesOf(item: FileItem): List<LibrarySearchMatch> =
        if (item.type == FileType.FILE && isSearching) {
            contentSearch?.matchesOf(item.id).orEmpty()
        } else {
            emptyList()
        }

    /** Whether a search, a filter or a section other than the folders changes what is shown. */
    val isNarrowed: Boolean get() = isSearching || filter != LibraryFilter.All || section != Section.HOME

    /** The current folder with the folders under it, at any depth; `null` in the root. */
    private val folderTreeShown: Set<String>? by derivedStateOf {
        val root = currentFolderId ?: return@derivedStateOf null
        val children = folders.groupBy { it.parentId }
        val tree = mutableSetOf(root)
        val pending = ArrayDeque(listOf(root))
        while (pending.isNotEmpty()) {
            for (child in children[pending.removeFirst()].orEmpty()) {
                if (tree.add(child.id)) pending.addLast(child.id)
            }
        }
        tree
    }

    /** The folders a search looks at, by [searchScope]. */
    private val foldersInScope: List<FolderSummary> by derivedStateOf {
        val tree = folderTreeShown
        when {
            searchScope == SearchScope.LIBRARY -> folders
            section != Section.HOME -> emptyList()
            tree == null -> folders
            else -> folders.filter { it.parentId in tree }
        }
    }

    /** The documents a search looks at, by [searchScope]. */
    private val documentsInScope: List<DocumentSummary> by derivedStateOf {
        val tree = folderTreeShown
        when {
            searchScope == SearchScope.LIBRARY -> documents
            section == Section.RECENT -> recentDocuments.mapTo(mutableSetOf()) { it.id }.let { recent -> documents.filter { it.id in recent } }
            section == Section.SHARED -> documents.filter { it.isShared }
            tree == null -> documents
            else -> documents.filter { it.folderId in tree }
        }
    }

    /**
     * What the library shows, in the chosen order: the content of the current folder (the folders
     * stay under a filter, the notes in them can be reached), the notes opened last or the shared
     * ones, the items whose name or content holds the text searched; only the notes of the filter.
     */
    val shownFiles: List<FileItem> by derivedStateOf {
        val query = searchQuery.trim()
        when {
            // The trash shows its own items (see shownTrash)
            section == Section.TRASH -> emptyList()
            query.isNotEmpty() -> searchResults(query).filterBy(filter)
            section == Section.RECENT -> recentDocuments.filterBy(filter)
            section == Section.SHARED -> sorted(documents.filter { it.isShared }.map { it.toFileItem() }.filterBy(filter))
            else -> sorted(currentDirectoryFiles.filterBy(filter, keepFolders = true))
        }
    }

    /** What is in the trash, narrowed by the search of its names. */
    val shownTrash: List<TrashedItem> by derivedStateOf {
        val query = searchQuery.trim()
        if (query.isEmpty()) trash else trash.filter { it.name.contains(query, ignoreCase = true) }
    }

    private fun sorted(items: List<FileItem>) = sortFiles(items, sortOption, sortAscending, keepFoldersOnTop)

    /**
     * The items in the scope of the search holding [query]: the folders by name, then the documents
     * in the order of the search of the contents and those it did not find by name; by the chosen
     * order without it.
     */
    private fun searchResults(query: String): List<FileItem> {
        val matchingFolders = foldersInScope.filter { it.name.contains(query, ignoreCase = true) }
        val ranked = contentSearch?.matchedDocumentIds
        if (ranked == null) {
            val matchingDocuments = documentsInScope.filter { it.name.contains(query, ignoreCase = true) }
            return sorted(matchingFolders.map { it.toFileItem() } + matchingDocuments.map { it.toFileItem() })
        }
        val inScope = documentsInScope.associateBy { it.id }
        val rankedIds = ranked.toSet()
        val rankedDocuments = ranked.mapNotNull { inScope[it]?.toFileItem() }
        val namedDocuments = documentsInScope
            .filter { it.id !in rankedIds && it.name.contains(query, ignoreCase = true) }
            .map { it.toFileItem() }
        return matchingFolders.sortedBy { it.name }.map { it.toFileItem() } + rankedDocuments + namedDocuments
    }

    private fun List<FileItem>.filterBy(filter: LibraryFilter, keepFolders: Boolean = false): List<FileItem> {
        if (filter == LibraryFilter.All) return this
        val tag = (filter as? LibraryFilter.Tagged)?.let { tagged -> allTags.firstOrNull { it.id == tagged.tagId } }
        return filter { item ->
            when {
                item.type == FileType.FOLDER -> keepFolders
                filter == LibraryFilter.Notes -> !item.hasPdf
                else -> tag != null && tag.contains(item.id)
            }
        }
    }

    /** The notes opened since they were made (a note is opened when it is made), the last first. */
    private val recentDocuments: List<FileItem> by derivedStateOf {
        documents.filter { it.lastOpenedAt > it.createdAt }.sortedByDescending { it.lastOpenedAt }
            .take(RECENT_SECTION_LIMIT).map { it.toFileItem() }
    }

    /** The notes of the root ([Section.HOME] in the navigation). */
    val homeNoteCount: Int by derivedStateOf { documents.count { it.folderId == null } }

    /** The notes of [Section.RECENT]. */
    val recentNoteCount: Int by derivedStateOf { recentDocuments.size }

    /** The notes of [Section.SHARED]. */
    val sharedNoteCount: Int by derivedStateOf { documents.count { it.isShared } }

    /** The folders from the root to the current one (the path shown inside a folder). */
    val currentPath: List<FolderSummary> by derivedStateOf {
        directoryIdSequence.mapNotNull { id -> folders.firstOrNull { it.id == id } }
    }

    /** The name of the folder [folderId], `null` for the root or an unknown folder. */
    fun folderName(folderId: String?): String? = folderId?.let { id -> folders.firstOrNull { it.id == id }?.name }

    /**
     * Goes to [section] and to the folders of [path], from the root: every move of the library
     * passes from here, leaving the search and the selection. Another section starts with all its
     * notes.
     */
    private fun navigate(section: Section, path: List<String>) {
        if (section != this.section) filter = LibraryFilter.All
        directoryIdSequence.clear()
        directoryIdSequence.addAll(path)
        this.section = section
        setQuery("")
        clearSelection()
    }

    /** Shows [section]; the folders start again from the root. */
    fun showSection(section: Section) = navigate(section, emptyList())

    /** The only writer of [searchQuery], which [contentSearch] follows (the trash has only names). */
    private fun setQuery(query: String) {
        searchQuery = query
        if (query.isBlank()) searchScope = SearchScope.HERE
        contentSearch?.onQueryChange(if (section == Section.TRASH) "" else query)
    }

    /** Searches [query] in [searchScope] (empty: back to what was shown). */
    fun search(query: String) = setQuery(query)

    /** Whether a search was widened beyond what the library shows: the navigation marks nothing as shown. */
    val searchCoversLibrary: Boolean get() = isSearching && searchScope == SearchScope.LIBRARY

    /** Makes the search look in [scope]. */
    fun selectSearchScope(scope: SearchScope) {
        searchScope = scope
    }

    /** Shows the notes of [filter]. */
    fun selectFilter(filter: LibraryFilter) {
        this.filter = filter
    }

    /** The identifier of the folder of the root holding the current one, `null` in the root. */
    val currentRootFolderId: String? get() = directoryIdSequence.firstOrNull()

    /** The most recently opened documents. */
    val recentFiles: List<FileItem> by derivedStateOf {
        documents.sortedByDescending { it.lastOpenedAt }.take(RECENT_FILES_LIMIT).map { it.toFileItem() }
    }

    /** The items selected, by their [FileItem.key]: what they are is read from the library. */
    private val selectedKeys = mutableStateListOf<String>()

    /** The selected items among those shown, as they are now (renamed, moved, gone). */
    val selectedItems: List<FileItem> by derivedStateOf {
        val shown = shownFiles.associateBy { it.key }
        selectedKeys.mapNotNull { shown[it] }
    }

    /** Whether items are being selected (long press, or with the keys of the pointer). */
    val selectionMode: Boolean get() = selectedItems.isNotEmpty()

    /** The item whose details are shown beside a wide library, by its [FileItem.key]. */
    private var detailsKey by mutableStateOf<String?>(null)

    /** The item whose details are shown beside a wide library: it does not start a selection. */
    val detailsItem: FileItem? by derivedStateOf { detailsKey?.let { key -> shownFiles.firstOrNull { it.key == key } } }

    /** What the commands act on: the selected items, or the item whose details are shown. */
    val targetItems: List<FileItem> get() = selectedItems.ifEmpty { listOfNotNull(detailsItem) }

    init {
        viewModelScope.launch { store.documents.collect { documents = it } }
        viewModelScope.launch { store.trash.collect { trash = it } }
        viewModelScope.launch {
            store.folders.collect { list ->
                folders = list
                // A deleted folder cannot stay open
                while (directoryIdSequence.isNotEmpty() && list.none { it.id == directoryIdSequence.last() }) {
                    directoryIdSequence.removeLastOrNull()
                }
            }
        }
    }

    /**
     * SORTING
     */
    fun toggleSortDirection() {
        sortAscending = !sortAscending
    }

    fun toggleKeepFoldersOnTop() {
        keepFoldersOnTop = !keepFoldersOnTop
    }

    /** Selects the sort order: the names from A, the dates from the most recent. */
    fun selectSortOption(option: SortOption) {
        if (sortOption != option) {
            sortAscending = option == SortOption.NAME
        }
        sortOption = option
    }

    /**
     * NAVIGATION
     */
    /** Enters [folder], wherever it was found: its path is the one from the root. */
    fun enterFolder(folder: FileItem) {
        if (folder.type == FileType.FOLDER) showFolder(folder.id)
    }

    /** Shows the folder [folderId] (`null`: the root), its path from the root known by its parents. */
    fun showFolder(folderId: String?) {
        val path = generateSequence(folderId) { id -> folders.firstOrNull { it.id == id }?.parentId }.toList().asReversed()
        navigate(Section.HOME, path)
    }

    /** Goes back to the parent folder; `false` in the root. */
    fun backFolder(): Boolean {
        if (directoryIdSequence.isEmpty()) return false
        navigate(section, directoryIdSequence.dropLast(1))
        return true
    }

    /**
     * Whether the system back has something to do: clear the selection or the details, leave the
     * search (back where it started), leave a folder or a section.
     */
    val canNavigateBack: Boolean
        get() = targetItems.isNotEmpty() || searchQuery.isNotEmpty() || directoryIdSequence.isNotEmpty() || section != Section.HOME

    fun navigateBack() {
        when {
            targetItems.isNotEmpty() -> clearSelection()
            searchQuery.isNotEmpty() -> setQuery("")
            directoryIdSequence.isNotEmpty() -> backFolder()
            else -> showSection(Section.HOME)
        }
    }

    /** The sub-folders of [parentId] (`null`: the root), by name. */
    fun getSubFolders(parentId: String?): List<FolderSummary> =
        folders.filter { it.parentId == parentId }.sortedBy { it.name }

    /**
     * FILE OPERATIONS
     */

    /**
     * The error of a name for a new item in the current folder, or for the new name of
     * [renamedFile] in the folder holding it (a result of a search may be anywhere), `null` when
     * valid.
     */
    fun validateFileName(name: String, renamedFile: FileItem? = null): StringResource? {
        DocumentNames.syntaxError(name)?.let { return it }
        val taken = if (renamedFile != null) {
            existNameInFolder(name, renamedFile.folderId, except = renamedFile)
        } else {
            existNameInDirectory(name)
        }
        return if (taken) DrawViewRes.string.name_error_exists else null
    }

    fun existNameInDirectory(name: String, except: FileItem? = null): Boolean = existNameInFolder(name, currentFolderId, except)

    private fun existNameInFolder(name: String, folderId: String?, except: FileItem?): Boolean =
        folders.any { it.parentId == folderId && it.name == name && "${FileType.FOLDER.name}:${it.id}" != except?.key } ||
            documents.any { it.folderId == folderId && it.name == name && "${FileType.FILE.name}:${it.id}" != except?.key }

    /** [name], or the first of "name (2)", "name (3)"… free in the current folder. */
    fun availableName(name: String): String {
        val base = name.trim().replace('/', '-').replace('\\', '-').ifEmpty { "Document" }
        if (!existNameInDirectory(base)) return base
        return generateSequence(2) { it + 1 }.map { "$base ($it)" }.first { !existNameInDirectory(it) }
    }

    fun createFile(
        type: FileType,
        name: String,
        onSuccess: () -> Unit = {},
        onError: (StringResource) -> Unit = {}
    ) {
        validateFileName(name)?.let { error ->
            onError(error)
            return
        }
        val folderId = currentFolderId
        viewModelScope.launch {
            val created = when (type) {
                FileType.FOLDER -> store.createFolder(name, folderId)
                FileType.FILE -> {
                    // The new note is not opened: its content is released right away
                    val document = store.createDocument(name, folderId = folderId)
                    store.closeDocument(document.id)
                    true
                }
            }
            if (created) {
                // Made during a search or in a section it would not be seen: it is shown where it went
                if (isSearching || section != Section.HOME) showFolder(folderId)
                onSuccess()
            } else {
                onError(Res.string.explorer_error_create_failed)
            }
        }
    }

    /** The error of [name] for a new note in [folderId] (`null`: the root), `null` when valid. */
    fun validateNoteName(name: String, folderId: String?): StringResource? {
        DocumentNames.syntaxError(name)?.let { return it }
        if (documents.any { it.folderId == folderId && it.name == name }) return DrawViewRes.string.name_error_exists
        return null
    }

    /** [name], or the first of "name (2)", "name (3)"… free for a note of [folderId]. */
    fun availableNoteName(name: String, folderId: String?): String {
        if (documents.none { it.folderId == folderId && it.name == name }) return name
        return generateSequence(2) { it + 1 }.map { "$name ($it)" }.first { candidate -> documents.none { it.folderId == folderId && it.name == candidate } }
    }

    /**
     * Makes the note [name] on the paper [background] in [folderId] (`null`: the root), then calls
     * [onCreated] with it (to open it).
     */
    fun createNote(
        name: String,
        background: PageBackground,
        folderId: String?,
        onCreated: (String) -> Unit = {},
        onError: (StringResource) -> Unit = {}
    ) {
        validateNoteName(name, folderId)?.let { error ->
            onError(error)
            return
        }
        viewModelScope.launch {
            val document = store.createDocument(name, background = background, folderId = folderId)
            // Opened by the editor, which loads it again
            store.closeDocument(document.id)
            onCreated(document.id)
        }
    }

    /** Every folder with its path from the root ("Analysis › Limits"), by path (where a note can go). */
    val folderPaths: List<Pair<String, String>> by derivedStateOf {
        val byId = folders.associateBy { it.id }
        folders.map { folder ->
            folder.id to generateSequence(folder) { byId[it.parentId ?: return@generateSequence null] }.toList().asReversed().joinToString(" › ") { it.name }
        }.sortedBy { it.second.lowercase() }
    }

    /** Whether the library shows the content of a folder: not a section, nor the results of a search. */
    val showsFolder: Boolean get() = section == Section.HOME && !isSearching

    private val folderPathsById: Map<String, String> by derivedStateOf { folderPaths.toMap() }

    /** The path of the folder [folderId] from the root ("Analysis › Limits"), `null` for the root. */
    fun folderPath(folderId: String?): String? = folderId?.let { folderPathsById[it] }

    /** The folder shown (`null`: the root), where a new note goes by default. */
    val currentFolderIdOrNull: String? get() = currentFolderId.takeIf { section == Section.HOME }

    fun renameFile(file: FileItem, newName: String) {
        viewModelScope.launch {
            when (file.type) {
                FileType.FOLDER -> store.renameFolder(file.id, newName)
                FileType.FILE -> openDocuments.rename(file.id, newName)
            }
        }
    }

    fun deleteFile(file: FileItem) = deleteFiles(listOf(file))

    fun moveFile(file: FileItem, targetFolderId: String?) = moveFiles(listOf(file), targetFolderId)

    /**
     * Whether [files] can be moved into [targetFolderId]: one of them at least is elsewhere (each
     * has its own folder, in a search or a section), and no folder would go into itself or into a
     * folder under it.
     */
    fun isValidMove(targetFolderId: String?, files: List<FileItem> = selectedItems): Boolean {
        if (files.none { it.folderId != targetFolderId }) return false
        val parents = folders.associate { it.id to it.parentId }
        val targetPath = generateSequence(targetFolderId) { parents[it] }.toSet()
        return files.none { it.type == FileType.FOLDER && it.id in targetPath }
    }

    private suspend fun trash(file: FileItem): Boolean = when (file.type) {
        FileType.FOLDER -> store.trashFolder(file.id)
        FileType.FILE -> store.trashDocument(file.id)
    }

    private suspend fun move(file: FileItem, targetFolderId: String?): Boolean = when (file.type) {
        FileType.FOLDER -> store.moveFolder(file.id, targetFolderId)
        FileType.FILE -> store.moveDocument(file.id, targetFolderId)
    }

    /**
     * SELECTION
     */
    /** Whether [file] is marked: selected, or with its details shown. */
    fun isSelected(file: FileItem): Boolean = file.key in selectedKeys || file.key == detailsKey

    /** The item the selection extends from ([extendSelection]): the last one selected. */
    private var selectionAnchor: String? = null

    /** A selection starting while the details of an item are shown starts from that item. */
    private fun selectDetailsItem() {
        val key = detailsKey ?: return
        if (key !in selectedKeys) selectedKeys.add(key)
        detailsKey = null
    }

    fun toggleSelection(file: FileItem) {
        selectDetailsItem()
        if (!selectedKeys.remove(file.key)) {
            selectedKeys.add(file.key)
            selectionAnchor = file.key
        }
    }

    /** Shows the details of [file] beside a wide library, in place of a selection. */
    fun showDetails(file: FileItem) {
        selectedKeys.clear()
        detailsKey = file.key
        selectionAnchor = file.key
    }

    /**
     * Extends the selection from the item last selected to [file] (Shift with a click), in the
     * order of the folder; without one, selects [file].
     */
    fun extendSelection(file: FileItem) {
        selectDetailsItem()
        val items = shownFiles
        val from = selectionAnchor?.let { anchor -> items.indexOfFirst { it.key == anchor } }?.takeIf { it >= 0 }
        val to = items.indexOfFirst { it.key == file.key }
        if (from == null || to < 0) {
            if (file.key !in selectedKeys) toggleSelection(file)
            return
        }
        for (item in items.subList(minOf(from, to), maxOf(from, to) + 1)) {
            if (item.key !in selectedKeys) selectedKeys.add(item.key)
        }
    }

    /** Leaves the selection and the details of an item. */
    fun clearSelection() {
        selectedKeys.clear()
        detailsKey = null
        selectionAnchor = null
    }

    fun selectAll() {
        detailsKey = null
        selectedKeys.clear()
        selectedKeys.addAll(shownFiles.map { it.key })
    }

    fun deleteSelected() = deleteFiles(selectedItems.toList())

    fun moveSelected(targetFolderId: String?) = moveFiles(selectedItems.toList(), targetFolderId)

    /** Moves [files] (the selected ones, or one of a menu) to the trash, leaving the selection; told by [trashNotice]. */
    fun deleteFiles(files: List<FileItem>) {
        unselect(files)
        viewModelScope.launch {
            val trashed = files.filter { trash(it) }
            if (trashed.isNotEmpty()) trashNotice = TrashNotice(trashed.map { it.name }, trashed.map { it.id })
        }
    }

    /** [notice] was told: it is not told again. */
    fun trashNoticeShown(notice: TrashNotice) {
        if (trashNotice === notice) trashNotice = null
    }

    /** Puts back what [notice] moved to the trash. */
    fun undoTrash(notice: TrashNotice) {
        viewModelScope.launch { notice.ids.forEach { store.restore(it) } }
    }

    /** Puts [item] back where it was (see `DocumentCatalog.restore`). */
    fun restore(item: TrashedItem) {
        viewModelScope.launch { store.restore(item.id) }
    }

    /** Deletes [item] of the trash for good. */
    fun deleteForGood(item: TrashedItem) {
        viewModelScope.launch {
            when (item.kind) {
                TrashedItem.Kind.DOCUMENT -> store.deleteDocument(item.id)
                TrashedItem.Kind.FOLDER -> store.deleteFolder(item.id)
            }
        }
    }

    /** Deletes for good everything in the trash. */
    fun emptyTrash() {
        viewModelScope.launch { store.purgeTrash(before = Long.MAX_VALUE) }
    }

    /** Moves [files] into [targetFolderId] (`null`: the root), leaving the selection. */
    fun moveFiles(files: List<FileItem>, targetFolderId: String?) {
        unselect(files)
        viewModelScope.launch {
            files.filter { it.folderId != targetFolderId }.forEach { move(it, targetFolderId) }
        }
    }

    private fun unselect(files: List<FileItem>) {
        val keys = files.mapTo(mutableSetOf()) { it.key }
        selectedKeys.removeAll { it in keys }
        if (detailsKey in keys) detailsKey = null
    }

    private companion object {
        const val RECENT_FILES_LIMIT = 10

        /** The notes of the section of the recent ones. */
        const val RECENT_SECTION_LIMIT = 30
    }
}

/**
 * Sorts the items of a folder by [option], keeping the folders before the documents when
 * [foldersOnTop] (the relative order of equal items is preserved).
 */
internal fun sortFiles(
    items: List<FileExplorerViewModel.FileItem>,
    option: FileExplorerViewModel.SortOption,
    ascending: Boolean,
    foldersOnTop: Boolean
): List<FileExplorerViewModel.FileItem> {
    val comparator: Comparator<FileExplorerViewModel.FileItem> = when (option) {
        FileExplorerViewModel.SortOption.NAME -> compareBy { it.name.lowercase() }
        FileExplorerViewModel.SortOption.DATE_CREATED -> compareBy { it.createdAt }
        FileExplorerViewModel.SortOption.DATE_MODIFIED -> compareBy { it.modifiedAt }
        FileExplorerViewModel.SortOption.LAST_OPENED -> compareBy { it.lastOpenedAt ?: 0L }
    }
    val sorted = items.sortedWith(if (ascending) comparator else comparator.reversed())
    return if (foldersOnTop) sorted.sortedBy { it.type != FileExplorerViewModel.FileType.FOLDER } else sorted
}

private fun DocumentSummary.toFileItem() = FileExplorerViewModel.FileItem(
    type = FileExplorerViewModel.FileType.FILE,
    id = id,
    name = name,
    createdAt = createdAt,
    modifiedAt = modifiedAt,
    lastOpenedAt = lastOpenedAt,
    isShared = isShared,
    folderId = folderId,
    pageCount = pageCount,
    hasPdf = hasPdf
)
