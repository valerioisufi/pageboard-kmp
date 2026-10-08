package io.github.valerioisufi.drawview.explorer

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.valerioisufi.drawview.data.DocumentStore
import io.github.valerioisufi.drawview.data.OpenDocuments
import io.github.valerioisufi.drawview.data.DocumentSummary
import io.github.valerioisufi.drawview.data.FolderSummary
import io.github.valerioisufi.drawview.explorer.generated.resources.Res
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_error_create_failed
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_error_name_empty
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_error_name_exists
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_error_name_invalid_chars
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

/**
 * State of the document library: navigation between the folders, sorting, selection and the
 * operations on documents and folders. The content follows the changes of the [store]; the
 * documents are renamed by a change of them ([openDocuments]: the devices they are shared with rename
 * them too).
 */
class FileExplorerViewModel(
    private val store: DocumentStore,
    private val openDocuments: OpenDocuments = OpenDocuments(store)
) : ViewModel() {

    enum class FileType { FILE, FOLDER }

    /** A document or a folder of the library ([isShared]: a document shared with other devices). */
    data class FileItem(
        val type: FileType,
        val id: String,
        val name: String,
        val createdAt: Long,
        val modifiedAt: Long,
        val lastOpenedAt: Long? = null,
        val isShared: Boolean = false
    ) {
        /** Identity of the item, stable across renames and moves. */
        val key: String get() = "${type.name}:$id"
    }

    enum class SortOption {
        NAME, DATE_CREATED, DATE_MODIFIED, LAST_OPENED
    }

    private var documents by mutableStateOf(emptyList<DocumentSummary>())
    private var folders by mutableStateOf(emptyList<FolderSummary>())

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

    /** The identifier of the folder of the root holding the current one, `null` in the root. */
    val currentRootFolderId: String? get() = directoryIdSequence.firstOrNull()

    /** The most recently opened documents. */
    val recentFiles: List<FileItem> by derivedStateOf {
        documents.sortedByDescending { it.lastOpenedAt }.take(RECENT_FILES_LIMIT).map { it.toFileItem() }
    }

    /** Whether items are being selected (long press), and the selected items. */
    var selectionMode by mutableStateOf(false)
        private set
    val selectedItems = mutableStateListOf<FileItem>()

    init {
        viewModelScope.launch { store.documents.collect { documents = it } }
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
    fun enterFolder(folder: FileItem) {
        if (folder.type == FileType.FOLDER) {
            directoryIdSequence.add(folder.id)
        }
    }

    /** Shows the folder [folderId] (`null`: the root), its path from the root known by its parents. */
    fun showFolder(folderId: String?) {
        val path = generateSequence(folderId) { id -> folders.firstOrNull { it.id == id }?.parentId }.toList().asReversed()
        directoryIdSequence.clear()
        directoryIdSequence.addAll(path)
    }

    /** Goes back to the parent folder; `false` in the root. */
    fun backFolder(): Boolean = directoryIdSequence.removeLastOrNull() != null

    /** Whether the system back has something to do: clear the selection or leave a folder. */
    val canNavigateBack: Boolean get() = selectionMode || directoryIdSequence.isNotEmpty()

    fun navigateBack() {
        if (selectionMode) clearSelection() else backFolder()
    }

    /** The sub-folders of [parentId] (`null`: the root), by name. */
    fun getSubFolders(parentId: String?): List<FolderSummary> =
        folders.filter { it.parentId == parentId }.sortedBy { it.name }

    /**
     * FILE OPERATIONS
     */

    /** The error of a name for a new item (or for the new name of [renamedFile]), `null` when valid. */
    fun validateFileName(name: String, renamedFile: FileItem? = null): StringResource? {
        if (name.isBlank()) return Res.string.explorer_error_name_empty
        if (name.contains("/") || name.contains("\\")) return Res.string.explorer_error_name_invalid_chars
        if (existNameInDirectory(name, except = renamedFile)) return Res.string.explorer_error_name_exists
        return null
    }

    fun existNameInDirectory(name: String, except: FileItem? = null): Boolean =
        currentDirectoryFiles.any { it.name == name && it.key != except?.key }

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
            if (created) onSuccess() else onError(Res.string.explorer_error_create_failed)
        }
    }

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
     * Whether [files] can be moved into [targetFolderId]: not into the current folder (nothing
     * would change) nor into one of the moved folders.
     */
    fun isValidMove(targetFolderId: String?, files: List<FileItem> = selectedItems): Boolean {
        if (targetFolderId == currentFolderId) return false
        return files.none { it.type == FileType.FOLDER && it.id == targetFolderId }
    }

    private suspend fun delete(file: FileItem): Boolean = when (file.type) {
        FileType.FOLDER -> store.deleteFolder(file.id)
        FileType.FILE -> store.deleteDocument(file.id)
    }

    private suspend fun move(file: FileItem, targetFolderId: String?): Boolean = when (file.type) {
        FileType.FOLDER -> store.moveFolder(file.id, targetFolderId)
        FileType.FILE -> store.moveDocument(file.id, targetFolderId)
    }

    /**
     * SELECTION
     */
    fun isSelected(file: FileItem): Boolean = selectedItems.any { it.key == file.key }

    /** The item the selection extends from ([extendSelection]): the last one selected. */
    private var selectionAnchor: FileItem? = null

    fun toggleSelection(file: FileItem) {
        val index = selectedItems.indexOfFirst { it.key == file.key }
        if (index >= 0) {
            selectedItems.removeAt(index)
            if (selectedItems.isEmpty()) {
                selectionMode = false
            }
        } else {
            selectedItems.add(file)
            selectionMode = true
            selectionAnchor = file
        }
    }

    /** Selects [file] alone (a wide library shows its details beside). */
    fun selectOnly(file: FileItem) {
        selectedItems.clear()
        selectedItems.add(file)
        selectionMode = true
        selectionAnchor = file
    }

    /**
     * Extends the selection from the item last selected to [file] (Shift with a click), in the
     * order of the folder; without one, selects [file].
     */
    fun extendSelection(file: FileItem) {
        val items = currentDirectoryFiles
        val from = selectionAnchor?.let { anchor -> items.indexOfFirst { it.key == anchor.key } }?.takeIf { it >= 0 }
        val to = items.indexOfFirst { it.key == file.key }
        if (from == null || to < 0) {
            if (!isSelected(file)) toggleSelection(file)
            return
        }
        for (item in items.subList(minOf(from, to), maxOf(from, to) + 1)) {
            if (!isSelected(item)) selectedItems.add(item)
        }
        selectionMode = true
    }

    fun clearSelection() {
        selectedItems.clear()
        selectionMode = false
        selectionAnchor = null
    }

    fun selectAll() {
        selectedItems.clear()
        selectedItems.addAll(currentDirectoryFiles)
        selectionMode = true
    }

    fun deleteSelected() = deleteFiles(selectedItems.toList())

    fun moveSelected(targetFolderId: String?) = moveFiles(selectedItems.toList(), targetFolderId)

    /** Deletes [files] (the selected ones, or one of a menu), leaving the selection. */
    fun deleteFiles(files: List<FileItem>) {
        unselect(files)
        viewModelScope.launch {
            files.forEach { delete(it) }
        }
    }

    /** Moves [files] into [targetFolderId] (`null`: the root), leaving the selection. */
    fun moveFiles(files: List<FileItem>, targetFolderId: String?) {
        unselect(files)
        viewModelScope.launch {
            files.forEach { move(it, targetFolderId) }
        }
    }

    private fun unselect(files: List<FileItem>) {
        val keys = files.mapTo(mutableSetOf()) { it.key }
        selectedItems.removeAll { it.key in keys }
        if (selectedItems.isEmpty()) selectionMode = false
    }

    private companion object {
        const val RECENT_FILES_LIMIT = 10
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

private fun FolderSummary.toFileItem() = FileExplorerViewModel.FileItem(
    type = FileExplorerViewModel.FileType.FOLDER,
    id = id,
    name = name,
    createdAt = createdAt,
    modifiedAt = modifiedAt
)

private fun DocumentSummary.toFileItem() = FileExplorerViewModel.FileItem(
    type = FileExplorerViewModel.FileType.FILE,
    id = id,
    name = name,
    createdAt = createdAt,
    modifiedAt = modifiedAt,
    lastOpenedAt = lastOpenedAt,
    isShared = isShared
)
