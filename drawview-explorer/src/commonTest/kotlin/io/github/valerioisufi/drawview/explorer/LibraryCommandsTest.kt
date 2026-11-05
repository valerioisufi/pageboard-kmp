package io.github.valerioisufi.drawview.explorer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.IosShare
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel.FileType
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel.SortOption
import io.github.valerioisufi.drawview.explorer.ui.LibraryCommands
import io.github.valerioisufi.drawview.explorer.ui.LibraryDialog
import io.github.valerioisufi.drawview.store.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.store.KeyValueDocumentStore
import io.github.valerioisufi.drawview.store.OpenDocuments
import io.github.valerioisufi.drawview.ui.extension.LibraryItemsAction
import io.github.valerioisufi.drawview.ui.extension.LibrarySelection
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        var selection: LibrarySelection? = null
        var searchFocused = false
    }

    private val export = LibraryItemsAction(Icons.Outlined.IosShare, { "Export" }, { "Export folder" }) { _, _ -> }

    private fun TestScope.library(): Pair<FileExplorerViewModel, Pair<LibraryCommands, Shown>> {
        val viewModel = FileExplorerViewModel(OpenDocuments(KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = backgroundScope)))
        val shown = Shown()
        val commands = LibraryCommands(
            viewModel = viewModel,
            openDocument = { shown.opened = it },
            itemsActions = listOf(export),
            showDialog = { shown.dialog = it },
            chooseAction = { _, selection -> shown.selection = selection },
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
        assertFalse(commands.selectionActionCommands.single().isEnabled())
        assertTrue(commands.folderActionCommands.single().isEnabled())
        // The action of the menus and of the shortcut: on the Home
        commands.itemsActionCommands.single().perform()
        assertNull(assertIs<LibrarySelection.Folder>(shown.selection).folderId)
        assertFalse(commands.enclosingFolder.isEnabled())

        commands.selectAll.perform()
        assertEquals(2, viewModel.selectedItems.size)
        assertFalse(commands.rename.isEnabled())
        // Several items together: their action takes them all
        commands.selectionActionCommands.single().perform()
        val both = assertIs<LibrarySelection.Items>(shown.selection)
        assertEquals(listOf(viewModel.item("Note").id), both.documentIds)
        assertEquals(listOf(viewModel.item("Math").id), both.folderIds)
        // Deleting moves to the trash at once, without a dialog
        commands.delete.perform()
        assertNull(shown.dialog)
        assertEquals(setOf("Note", "Math"), viewModel.trashNotice?.names?.toSet())
        assertTrue(viewModel.currentDirectoryFiles.isEmpty())
        viewModel.undoTrash(assertNotNull(viewModel.trashNotice))
        assertEquals(setOf("Note", "Math"), viewModel.currentDirectoryFiles.map { it.name }.toSet())

        // A document selected alone: renamed, its actions
        viewModel.clearSelection()
        viewModel.toggleSelection(viewModel.item("Note"))
        assertTrue(commands.rename.isEnabled())
        commands.itemsActionCommands.single().perform()
        assertEquals(listOf(viewModel.item("Note").id), assertIs<LibrarySelection.Items>(shown.selection).documentIds)

        // A folder selected alone: renamed, its folder actions
        viewModel.clearSelection()
        viewModel.toggleSelection(viewModel.item("Math"))
        assertTrue(commands.rename.isEnabled())
        commands.selectionActionCommands.single().perform()
        assertEquals(listOf(viewModel.item("Math").id), assertIs<LibrarySelection.Items>(shown.selection).folderIds)
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
        noteCommands.getValue("items-action-0").perform()
        assertEquals(listOf(note.id), assertIs<LibrarySelection.Items>(shown.selection).documentIds)

        // A folder: opened in the library, with the actions of the application
        val math = viewModel.item("Math")
        val folderCommands = commands.forItem(math).associateBy { it.id }
        folderCommands.getValue("items-action-0").perform()
        assertEquals(listOf(math.id), assertIs<LibrarySelection.Items>(shown.selection).folderIds)

        folderCommands.getValue("open").perform()
        assertEquals("Math", viewModel.currentFolder?.name)
        assertTrue(commands.enclosingFolder.isEnabled())

        // Inside Math: the action of the folder shown operates on Math
        commands.folderActionCommands.single().perform()
        assertEquals(math.id, assertIs<LibrarySelection.Folder>(shown.selection).folderId)

        commands.enclosingFolder.perform()
        assertEquals(null, viewModel.currentFolder)

        // Back in Home: the action of the folder shown operates on Home (folderId = null)
        commands.folderActionCommands.single().perform()
        assertNull(assertIs<LibrarySelection.Folder>(shown.selection).folderId)
    }

    @Test
    fun theCommandsOfTheFolderShownWaitForTheEndOfASearch() = runTest {
        val (viewModel, pair) = library()
        val (commands, shown) = pair
        viewModel.createFile(FileType.FOLDER, "Math")
        viewModel.enterFolder(viewModel.item("Math"))
        viewModel.createFile(FileType.FILE, "Note")
        assertTrue(commands.enclosingFolder.isEnabled())
        assertTrue(commands.folderActionCommands.single().isEnabled())

        viewModel.search("note")
        assertFalse(commands.enclosingFolder.isEnabled(), "going up would change nothing on screen")
        assertFalse(commands.folderActionCommands.single().isEnabled(), "the folder is not what is on screen")
        assertFalse(commands.itemsActionCommands.single().isEnabled())
        commands.itemsActionCommands.single().perform()
        assertNull(shown.selection)

        // Selecting all follows what is on screen: here an empty folder, and a result elsewhere
        viewModel.search("")
        viewModel.showFolder(null)
        viewModel.createFile(FileType.FOLDER, "Empty")
        viewModel.enterFolder(viewModel.item("Empty"))
        assertFalse(commands.selectAll.isEnabled())
        viewModel.search("note")
        viewModel.selectSearchScope(FileExplorerViewModel.SearchScope.LIBRARY)
        assertTrue(commands.selectAll.isEnabled())
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
    fun theTrashRestoresAndDeletesForGoodAskingFirst() = runTest {
        val (viewModel, pair) = library()
        val (commands, shown) = pair
        viewModel.createFile(FileType.FILE, "Note")
        viewModel.createFile(FileType.FOLDER, "Math")
        viewModel.deleteFile(viewModel.item("Note"))
        viewModel.deleteFile(viewModel.item("Math"))
        assertEquals(listOf("Math"), viewModel.trashNotice?.names, "the last one told")
        assertEquals(setOf("Note", "Math"), viewModel.trash.map { it.name }.toSet())

        viewModel.showSection(FileExplorerViewModel.Section.TRASH)
        assertTrue(viewModel.shownFiles.isEmpty(), "the trash shows its own items")
        viewModel.restore(viewModel.trash.single { it.name == "Math" })
        assertEquals(listOf("Note"), viewModel.trash.map { it.name })

        commands.deleteForGood(viewModel.trash.single())
        val asked = assertIs<LibraryDialog.DeleteForGood>(shown.dialog)
        viewModel.deleteForGood(asked.item)
        assertTrue(viewModel.trash.isEmpty())

        viewModel.showFolder(null)
        viewModel.deleteFile(viewModel.item("Math"))
        commands.emptyTrash()
        assertEquals(LibraryDialog.EmptyTrash, shown.dialog)
        viewModel.emptyTrash()
        assertTrue(viewModel.trash.isEmpty())
        assertTrue(viewModel.currentDirectoryFiles.isEmpty())
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
