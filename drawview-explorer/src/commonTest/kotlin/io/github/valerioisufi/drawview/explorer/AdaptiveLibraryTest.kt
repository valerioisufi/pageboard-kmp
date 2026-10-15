package io.github.valerioisufi.drawview.explorer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel.FileType
import io.github.valerioisufi.drawview.explorer.ui.LibraryDrag
import io.github.valerioisufi.drawview.explorer.ui.LibraryLayout
import io.github.valerioisufi.drawview.store.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.store.KeyValueDocumentStore
import io.github.valerioisufi.drawview.store.OpenDocuments
import io.github.valerioisufi.drawview.ui.adaptive.ReferenceWindow
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
class AdaptiveLibraryTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.library() = FileExplorerViewModel(OpenDocuments(KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = backgroundScope)))

    private fun FileExplorerViewModel.item(name: String) = currentDirectoryFiles.single { it.name == name }

    @Test
    fun theLibraryIsAListAGridOrPanesByItsWidth() {
        val layouts = ReferenceWindow.entries.associateWith { LibraryLayout.of(it.width) }
        assertEquals(LibraryLayout.LIST, layouts[ReferenceWindow.PHONE])
        assertEquals(LibraryLayout.GRID, layouts[ReferenceWindow.PHONE_LANDSCAPE])
        assertEquals(LibraryLayout.GRID, layouts[ReferenceWindow.TABLET_PORTRAIT])
        assertEquals(LibraryLayout.GRID, layouts[ReferenceWindow.TABLET_SPLIT_SCREEN])
        assertEquals(LibraryLayout.PANES, layouts[ReferenceWindow.TABLET_LANDSCAPE])
        assertEquals(LibraryLayout.GRID, layouts[ReferenceWindow.DESKTOP_NARROW])
        assertEquals(LibraryLayout.PANES, layouts[ReferenceWindow.DESKTOP])
        assertEquals(LibraryLayout.GRID, LibraryLayout.of(LibraryLayout.PanesMinWidth - 1.dp))
    }

    @Test
    fun shiftExtendsTheSelectionInTheOrderOfTheFolder() = runTest {
        val viewModel = library()
        for (name in listOf("A", "B", "C", "D", "E")) viewModel.createFile(FileType.FILE, name)

        viewModel.toggleSelection(viewModel.item("B"))
        viewModel.extendSelection(viewModel.item("D"))
        assertEquals(listOf("B", "C", "D"), viewModel.selectedItems.map { it.name }.sorted())

        // From the last one selected
        viewModel.toggleSelection(viewModel.item("A"))
        viewModel.extendSelection(viewModel.item("A"))
        assertEquals(listOf("A", "B", "C", "D"), viewModel.selectedItems.map { it.name }.sorted())

        // Without a selection, the item alone
        viewModel.clearSelection()
        viewModel.extendSelection(viewModel.item("E"))
        assertEquals(listOf("E"), viewModel.selectedItems.map { it.name })

        viewModel.selectOnly(viewModel.item("C"))
        assertEquals(listOf("C"), viewModel.selectedItems.map { it.name })
        assertTrue(viewModel.selectionMode)
    }

    @Test
    fun theNavigationPaneShowsAFolderFromTheRoot() = runTest {
        val viewModel = library()
        viewModel.createFile(FileType.FOLDER, "Physics")
        viewModel.enterFolder(viewModel.item("Physics"))
        viewModel.createFile(FileType.FOLDER, "Mechanics")
        val mechanics = viewModel.item("Mechanics").id
        val physics = viewModel.currentFolder!!.id

        viewModel.showFolder(null)
        assertNull(viewModel.currentFolder)
        assertEquals(listOf("Physics"), viewModel.rootFolders.map { it.name })

        // A folder inside another: its path from the root, so that back goes to its parent
        viewModel.showFolder(mechanics)
        assertEquals("Mechanics", viewModel.currentFolder?.name)
        assertEquals(physics, viewModel.currentRootFolderId)
        assertTrue(viewModel.backFolder())
        assertEquals("Physics", viewModel.currentFolder?.name)
    }

    @Test
    fun itemsDroppedOntoAFolderMoveThere() = runTest {
        val viewModel = library()
        viewModel.createFile(FileType.FOLDER, "Physics")
        viewModel.createFile(FileType.FILE, "Note")
        val physics = viewModel.item("Physics")
        val drag = LibraryDrag()
        drag.register("physics", physics.id, Rect(0f, 0f, 100f, 50f))
        drag.register("root", null, Rect(0f, 100f, 100f, 150f))

        // A folder cannot go into itself, nor the items where they are
        drag.start(listOf(physics), Offset(10f, 10f))
        assertFalse(drag.isValidDrop(viewModel, physics.id))
        assertFalse(drag.isValidDrop(viewModel, null))
        drag.end(viewModel)
        assertEquals(setOf("Physics", "Note"), viewModel.currentDirectoryFiles.map { it.name }.toSet())

        // Dropped beside the folders: nothing moves
        drag.start(listOf(viewModel.item("Note")), Offset(10f, 10f))
        drag.move(Offset(300f, 300f))
        drag.end(viewModel)
        assertEquals(setOf("Physics", "Note"), viewModel.currentDirectoryFiles.map { it.name }.toSet())

        // Onto the folder
        drag.start(listOf(viewModel.item("Note")), Offset(300f, 300f))
        drag.move(Offset(20f, 20f))
        assertEquals(physics.id, drag.hovered)
        drag.end(viewModel)
        assertFalse(drag.isDragging)
        assertEquals(listOf("Physics"), viewModel.currentDirectoryFiles.map { it.name })
        viewModel.enterFolder(physics)
        assertEquals(listOf("Note"), viewModel.currentDirectoryFiles.map { it.name })
    }
}
