package io.github.valerioisufi.drawview.explorer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.IosShare
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel.FileItem
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel.FileType
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel.SortOption
import io.github.valerioisufi.drawview.explorer.ui.LibraryCommands
import io.github.valerioisufi.drawview.explorer.ui.LibraryDialog
import io.github.valerioisufi.drawview.store.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.store.KeyValueDocumentStore
import io.github.valerioisufi.drawview.store.OpenDocuments
import io.github.valerioisufi.drawview.ui.extension.DocumentAction
import io.github.valerioisufi.drawview.ui.extension.FolderAction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryCommandsTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class Shown {
        var dialog: LibraryDialog? = null
        var opened: String? = null
        var action: Pair<DocumentAction, FileItem>? = null
        var folderAction: Triple<FolderAction, String?, String>? = null
        var searchFocused = false
    }

    private val export = DocumentAction(Icons.Outlined.IosShare, { "Export" }, { _, _, _ -> })
    private val exportFolder = FolderAction(Icons.Outlined.IosShare, { "Export folder" }, { _, _, _ -> })

    private fun TestScope.library(): Pair<FileExplorerViewModel, Pair<LibraryCommands, Shown>> {
        val viewModel = FileExplorerViewModel(OpenDocuments(KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = backgroundScope)))
        val shown = Shown()
        val commands = LibraryCommands(
            viewModel = viewModel,
            openDocument = { shown.opened = it },
            documentActions = listOf(export),
            folderActions = listOf(exportFolder),
            showDialog = { shown.dialog = it },
            chooseAction = { action, item -> shown.action = action to item },
            chooseFolderAction = { action, folderId, folderName -> shown.folderAction = Triple(action, folderId, folderName) },
            focusSearch = { shown.searchFocused = true }
        )
        return viewModel to (commands to shown)
    }

    private fun FileExplorerViewModel.item(name: String) = currentDirectoryFiles.single { it.name == name }

    @Test
    fun theCommandsOfTheSelectionFollowIt() = runTest {
        val (viewModel, pair) = library()
        val (commands, shown) = pair
        viewModel.createFile(FileType.FILE, "Note")
        viewModel.createFile(FileType.FOLDER, "Math")

        // Nothing selected: only what concerns the folder
        assertTrue(commands.newNote.isEnabled())
        assertFalse(commands.delete.isEnabled())
        assertFalse(commands.rename.isEnabled())
        assertFalse(commands.documentActionCommands.single().isEnabled())
        assertFalse(commands.folderActionCommands.single().isEnabled())
        assertTrue(commands.currentFolderActionCommands.single().isEnabled())
        assertFalse(commands.enclosingFolder.isEnabled())

        commands.selectAll.perform()
        assertEquals(2, viewModel.selectedItems.size)
        assertFalse(commands.rename.isEnabled())
        commands.delete.perform()
        assertEquals(setOf("Note", "Math"), assertIs<LibraryDialog.Delete>(shown.dialog).items.map { it.name }.toSet())

        // A document selected alone: renamed, its actions
        viewModel.clearSelection()
        viewModel.toggleSelection(viewModel.item("Note"))
        assertTrue(commands.rename.isEnabled())
        commands.documentActionCommands.single().perform()
        assertEquals("Note", shown.action?.second?.name)

        // A folder selected alone: renamed, its folder actions
        viewModel.clearSelection()
        viewModel.toggleSelection(viewModel.item("Math"))
        assertTrue(commands.rename.isEnabled())
        assertFalse(commands.documentActionCommands.single().isEnabled())
        assertTrue(commands.folderActionCommands.single().isEnabled())
        commands.folderActionCommands.single().perform()
        assertEquals("Math", shown.folderAction?.third)
    }

    @Test
    fun theCommandsOfAnItemActOnIt() = runTest {
        val (viewModel, pair) = library()
        val (commands, shown) = pair
        viewModel.createFile(FileType.FILE, "Note")
        viewModel.createFile(FileType.FOLDER, "Math")
        val note = viewModel.item("Note")

        val noteCommands = commands.forItem(note).associateBy { it.id }
        noteCommands.getValue("open").perform()
        assertEquals(note.id, shown.opened)
        noteCommands.getValue("move").perform()
        assertEquals(listOf(note), assertIs<LibraryDialog.Move>(shown.dialog).items)
        assertTrue("document-action-0" in noteCommands)

        // A folder: opened in the library, with folder actions and without document actions
        val folderCommands = commands.forItem(viewModel.item("Math")).associateBy { it.id }
        assertFalse("document-action-0" in folderCommands)
        assertTrue("folder-action-0" in folderCommands)
        folderCommands.getValue("folder-action-0").perform()
        assertEquals("Math", shown.folderAction?.third)

        folderCommands.getValue("open").perform()
        assertEquals("Math", viewModel.currentFolder?.name)
        assertTrue(commands.enclosingFolder.isEnabled())

        // Inside Math: current folder action operates on Math
        commands.currentFolderActionCommands.single().perform()
        assertEquals("Math", shown.folderAction?.third)
        assertEquals(viewModel.currentFolder?.id, shown.folderAction?.second)

        commands.enclosingFolder.perform()
        assertEquals(null, viewModel.currentFolder)

        // Back in Home: current folder action operates on Home (folderId = null)
        commands.currentFolderActionCommands.single().perform()
        assertEquals(null, shown.folderAction?.second)
    }

    @Test
    fun theSortCommandsAreItsStates() = runTest {
        val (viewModel, pair) = library()
        val (commands, _) = pair
        val byDate = commands.sortBy.single { it.id == "sort-date_modified" }
        assertFalse(byDate.isChecked!!.invoke())
        byDate.perform()
        assertEquals(SortOption.DATE_MODIFIED, viewModel.sortOption)
        assertTrue(byDate.isChecked!!.invoke())
        commands.invertOrder.perform()
        assertTrue(viewModel.sortAscending)
    }

    @Test
    fun deletingItemsLeavesTheSelection() = runTest {
        val (viewModel, _) = library()
        viewModel.createFile(FileType.FILE, "A")
        viewModel.createFile(FileType.FILE, "B")
        viewModel.toggleSelection(viewModel.item("A"))
        viewModel.toggleSelection(viewModel.item("B"))
        viewModel.deleteFiles(listOf(viewModel.item("A")))
        assertEquals(listOf("B"), viewModel.selectedItems.map { it.name })
        assertTrue(viewModel.selectionMode)
        viewModel.deleteFiles(listOf(viewModel.item("B")))
        assertFalse(viewModel.selectionMode)
    }
}
