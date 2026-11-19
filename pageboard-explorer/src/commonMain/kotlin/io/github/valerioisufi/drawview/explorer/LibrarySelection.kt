package io.github.valerioisufi.drawview.explorer

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The items selected in the library and the one whose details are shown beside a wide library
 * (High Cohesion), by their key among those [shown]: what they are is read from the library, as it
 * is now (renamed, moved, gone).
 */
internal class LibrarySelection(private val shown: () -> List<FileExplorerViewModel.FileItem>) {
    /** The items selected, by their [FileExplorerViewModel.FileItem.key]: what they are is read from the library. */
    private val selectedKeys = mutableStateListOf<String>()

    /** The selected items among those shown, as they are now (renamed, moved, gone). */
    val selectedItems: List<FileExplorerViewModel.FileItem> by derivedStateOf {
        val shown = shown().associateBy { it.key }
        selectedKeys.mapNotNull { shown[it] }
    }

    /** Whether items are being selected (long press, or with the keys of the pointer). */
    val selectionMode: Boolean get() = selectedItems.isNotEmpty()

    /** The item whose details are shown beside a wide library, by its [FileExplorerViewModel.FileItem.key]. */
    private var detailsKey by mutableStateOf<String?>(null)

    /** The item whose details are shown beside a wide library: it does not start a selection. */
    val detailsItem: FileExplorerViewModel.FileItem? by derivedStateOf { detailsKey?.let { key -> shown().firstOrNull { it.key == key } } }

    /** Whether [file] is marked: selected, or with its details shown. */
    fun isSelected(file: FileExplorerViewModel.FileItem): Boolean = file.key in selectedKeys || file.key == detailsKey

    /** The item the selection extends from ([extend]): the last one selected. */
    private var selectionAnchor: String? = null

    /** A selection starting while the details of an item are shown starts from that item. */
    private fun selectDetailsItem() {
        val key = detailsKey ?: return
        if (key !in selectedKeys) selectedKeys.add(key)
        detailsKey = null
    }

    fun toggle(file: FileExplorerViewModel.FileItem) {
        selectDetailsItem()
        if (!selectedKeys.remove(file.key)) {
            selectedKeys.add(file.key)
            selectionAnchor = file.key
        }
    }

    /** Shows the details of [file] beside a wide library, in place of a selection. */
    fun showDetails(file: FileExplorerViewModel.FileItem) {
        selectedKeys.clear()
        detailsKey = file.key
        selectionAnchor = file.key
    }

    /**
     * Extends the selection from the item last selected to [file] (Shift with a click), in the
     * order of the folder; without one, selects [file].
     */
    fun extend(file: FileExplorerViewModel.FileItem) {
        selectDetailsItem()
        val items = shown()
        val from = selectionAnchor?.let { anchor -> items.indexOfFirst { it.key == anchor } }?.takeIf { it >= 0 }
        val to = items.indexOfFirst { it.key == file.key }
        if (from == null || to < 0) {
            if (file.key !in selectedKeys) toggle(file)
            return
        }
        for (item in items.subList(minOf(from, to), maxOf(from, to) + 1)) {
            if (item.key !in selectedKeys) selectedKeys.add(item.key)
        }
    }

    /** Leaves the selection and the details of an item. */
    fun clear() {
        selectedKeys.clear()
        detailsKey = null
        selectionAnchor = null
    }

    fun selectAll() {
        detailsKey = null
        selectedKeys.clear()
        selectedKeys.addAll(shown().map { it.key })
    }

    /** Leaves [files] out of the selection (moved or in the trash). */
    fun unselect(files: List<FileExplorerViewModel.FileItem>) {
        val keys = files.mapTo(mutableSetOf()) { it.key }
        selectedKeys.removeAll { it in keys }
        if (detailsKey in keys) detailsKey = null
    }
}
