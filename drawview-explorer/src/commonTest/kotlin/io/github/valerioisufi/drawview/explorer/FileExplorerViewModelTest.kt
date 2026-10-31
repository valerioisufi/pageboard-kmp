package io.github.valerioisufi.drawview.explorer

import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel.FileItem
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel.FileType
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel.SortOption
import io.github.valerioisufi.drawview.ui.generated.resources.Res
import io.github.valerioisufi.drawview.ui.generated.resources.name_error_empty
import io.github.valerioisufi.drawview.ui.generated.resources.name_error_exists
import io.github.valerioisufi.drawview.ui.generated.resources.name_error_invalid_chars
import io.github.valerioisufi.drawview.store.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.store.KeyValueDocumentStore
import io.github.valerioisufi.drawview.store.OpenDocuments
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
class FileExplorerViewModelTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.createViewModel() =
        FileExplorerViewModel(OpenDocuments(KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = backgroundScope)))

    private val FileExplorerViewModel.names get() = currentDirectoryFiles.map { it.name }

    private fun FileExplorerViewModel.item(name: String) = currentDirectoryFiles.single { it.name == name }

    @Test
    fun createsItemsAndNavigatesTheFolders() = runTest {
        val viewModel = createViewModel()
        viewModel.createFile(FileType.FILE, "b note")
        viewModel.createFile(FileType.FOLDER, "Math")
        viewModel.createFile(FileType.FILE, "A note")
        assertEquals(listOf("Math", "A note", "b note"), viewModel.names)

        viewModel.toggleKeepFoldersOnTop()
        assertEquals(listOf("A note", "b note", "Math"), viewModel.names)
        viewModel.toggleSortDirection()
        assertEquals(listOf("Math", "b note", "A note"), viewModel.names)
        viewModel.toggleKeepFoldersOnTop()
        viewModel.toggleSortDirection()

        viewModel.enterFolder(viewModel.item("Math"))
        assertEquals("Math", viewModel.currentFolder?.name)
        assertTrue(viewModel.names.isEmpty())
        viewModel.createFile(FileType.FILE, "Inside")
        assertEquals(listOf("Inside"), viewModel.names)
        assertEquals(listOf("Inside", "A note", "b note").toSet(), viewModel.recentFiles.map { it.name }.toSet())

        assertTrue(viewModel.canNavigateBack)
        assertTrue(viewModel.backFolder())
        assertNull(viewModel.currentFolder)
        assertFalse(viewModel.backFolder())
        assertFalse(viewModel.canNavigateBack)
    }

    @Test
    fun validatesTheNamesInTheCurrentFolder() = runTest {
        val viewModel = createViewModel()
        viewModel.createFile(FileType.FOLDER, "Math")
        var error: Any? = null
        viewModel.createFile(FileType.FILE, "Math", onError = { error = it })
        assertEquals(Res.string.name_error_exists, error)
        assertEquals(listOf("Math"), viewModel.names)

        assertEquals(Res.string.name_error_empty, viewModel.validateFileName("  "))
        assertEquals(Res.string.name_error_invalid_chars, viewModel.validateFileName("a/b"))
        assertEquals(Res.string.name_error_invalid_chars, viewModel.validateFileName("a\\b"))
        assertNull(viewModel.validateFileName("Physics"))
        // An item can keep its own name when renamed
        assertNull(viewModel.validateFileName("Math", renamedFile = viewModel.item("Math")))

        viewModel.enterFolder(viewModel.item("Math"))
        assertNull(viewModel.validateFileName("Math"), "names are unique per folder")
    }

    @Test
    fun movesRenamesAndDeletesTheSelection() = runTest {
        val viewModel = createViewModel()
        viewModel.createFile(FileType.FOLDER, "Archive")
        viewModel.createFile(FileType.FILE, "One")
        viewModel.createFile(FileType.FILE, "Two")
        val archive = viewModel.item("Archive")

        viewModel.toggleSelection(viewModel.item("One"))
        viewModel.toggleSelection(viewModel.item("Two"))
        assertTrue(viewModel.selectionMode)
        assertFalse(viewModel.isValidMove(null), "the items are already in the root")
        assertTrue(viewModel.isValidMove(archive.id))
        viewModel.moveSelected(archive.id)
        assertFalse(viewModel.selectionMode)
        assertEquals(listOf("Archive"), viewModel.names)

        viewModel.toggleSelection(archive)
        assertFalse(viewModel.isValidMove(archive.id), "a folder cannot be moved into itself")
        viewModel.navigateBack()
        assertFalse(viewModel.selectionMode, "back clears the selection")

        viewModel.renameFile(archive, "Old")
        assertEquals(listOf("Old"), viewModel.names)

        viewModel.enterFolder(viewModel.item("Old"))
        assertEquals(listOf("One", "Two"), viewModel.names)
        viewModel.selectAll()
        assertEquals(2, viewModel.selectedItems.size)
        viewModel.deleteSelected()
        assertTrue(viewModel.names.isEmpty())

        viewModel.navigateBack()
        viewModel.deleteFile(viewModel.item("Old"))
        assertTrue(viewModel.names.isEmpty())
    }

    @Test
    fun sortsTheDatesFromTheMostRecent() {
        fun item(name: String, type: FileType, time: Long) = FileItem(type, name, name, time, time, time)
        val items = listOf(
            item("old", FileType.FILE, 1),
            item("folder", FileType.FOLDER, 2),
            item("new", FileType.FILE, 3)
        )
        assertEquals(
            listOf("folder", "new", "old"),
            sortFiles(items, SortOption.DATE_MODIFIED, ascending = false, foldersOnTop = true).map { it.name }
        )
        assertEquals(
            listOf("new", "folder", "old"),
            sortFiles(items, SortOption.LAST_OPENED, ascending = false, foldersOnTop = false).map { it.name }
        )
        assertEquals(
            listOf("old", "folder", "new"),
            sortFiles(items, SortOption.DATE_CREATED, ascending = true, foldersOnTop = false).map { it.name }
        )

        val viewModel = FileExplorerViewModel(OpenDocuments(KeyValueDocumentStore(InMemoryKeyValueStorage())))
        viewModel.selectSortOption(SortOption.DATE_MODIFIED)
        assertFalse(viewModel.sortAscending, "the dates start from the most recent")
        viewModel.selectSortOption(SortOption.NAME)
        assertTrue(viewModel.sortAscending, "the names start from A")
    }
}
