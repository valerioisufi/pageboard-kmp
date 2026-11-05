package io.github.valerioisufi.drawview.explorer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel.SearchScope
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel.Section
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

/** A search as a state of the library (docs/library-search.md): how it starts, where it looks, how it ends. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibrarySearchStateTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** A content search that records what it is told and finds what the test says. */
    private class RecordingSearch : LibrarySearch {
        var told = "<nothing>"
        var found by mutableStateOf<List<String>?>(null)
        override val matchedDocumentIds: List<String>? get() = found
        override fun onQueryChange(query: String) {
            told = query
        }
    }

    /**
     * Home: `Analysis` (`Limits`, and `Sequences` with `Limit laws`), `Physics` (`Motion`, `Waves`),
     * `Limit cycles`.
     */
    private fun TestScope.library(): FileExplorerViewModel {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = backgroundScope)
        val viewModel = FileExplorerViewModel(OpenDocuments(store))
        viewModel.createFile(FileExplorerViewModel.FileType.FOLDER, "Analysis")
        viewModel.createFile(FileExplorerViewModel.FileType.FOLDER, "Physics")
        viewModel.createFile(FileExplorerViewModel.FileType.FILE, "Limit cycles")
        viewModel.enterFolder(viewModel.named("Analysis"))
        viewModel.createFile(FileExplorerViewModel.FileType.FILE, "Limits")
        viewModel.createFile(FileExplorerViewModel.FileType.FOLDER, "Sequences")
        viewModel.enterFolder(viewModel.named("Sequences"))
        viewModel.createFile(FileExplorerViewModel.FileType.FILE, "Limit laws")
        viewModel.showFolder(null)
        viewModel.enterFolder(viewModel.named("Physics"))
        viewModel.createFile(FileExplorerViewModel.FileType.FILE, "Motion")
        viewModel.createFile(FileExplorerViewModel.FileType.FILE, "Waves")
        viewModel.showFolder(null)
        return viewModel
    }

    private fun FileExplorerViewModel.named(name: String) = shownFiles.single { it.name == name }

    private fun FileExplorerViewModel.folder(name: String) = rootFolders.single { it.name == name }

    private val FileExplorerViewModel.shownNames get() = shownFiles.map { it.name }

    @Test
    fun everyMoveLeavesTheSearchAndShowsWhatWasAsked() = runTest {
        val viewModel = library()

        viewModel.search("limit")
        viewModel.showSection(Section.RECENT)
        assertEquals("", viewModel.searchQuery)
        assertEquals(Section.RECENT, viewModel.section)

        viewModel.showFolder(null)
        viewModel.search("limit")
        viewModel.showSection(Section.TRASH)
        assertEquals("", viewModel.searchQuery)
        assertTrue(viewModel.shownFiles.isEmpty(), "the trash shows its own items")

        viewModel.showFolder(viewModel.folder("Physics").id)
        viewModel.search("limit")
        assertTrue(viewModel.backFolder())
        assertEquals("", viewModel.searchQuery)
        assertNull(viewModel.currentFolder)
        assertEquals(listOf("Analysis", "Physics", "Limit cycles"), viewModel.shownNames)
    }

    @Test
    fun backLeavesTheSearchAndStaysWhereItWas() = runTest {
        val viewModel = library()
        viewModel.showFolder(viewModel.folder("Physics").id)
        viewModel.search("mot")
        assertTrue(viewModel.canNavigateBack)

        viewModel.navigateBack()
        assertEquals("", viewModel.searchQuery)
        assertEquals("Physics", viewModel.currentFolder?.name)
        assertEquals(listOf("Motion", "Waves"), viewModel.shownNames)
    }

    @Test
    fun theContentSearchIsToldEveryChangeOfTheQuery() = runTest {
        val viewModel = library()
        val search = RecordingSearch()
        viewModel.contentSearch = search

        viewModel.search("limit")
        assertEquals("limit", search.told)
        viewModel.showFolder(viewModel.folder("Physics").id)
        assertEquals("", search.told, "going to a folder")

        viewModel.search("limit")
        viewModel.navigateBack()
        assertEquals("", search.told, "back")

        viewModel.search("limit")
        viewModel.showSection(Section.RECENT)
        assertEquals("", search.told, "going to a section")

        viewModel.showSection(Section.TRASH)
        viewModel.search("limit")
        assertEquals("", search.told, "the trash is searched by name")
    }

    @Test
    fun aSearchLooksInTheFolderShownWithItsSubFolders() = runTest {
        val viewModel = library()

        viewModel.search("limit")
        assertEquals(listOf("Limit cycles", "Limit laws", "Limits"), viewModel.shownNames, "from the root, everywhere")
        assertFalse(viewModel.canWidenSearch)

        viewModel.showFolder(viewModel.folder("Analysis").id)
        viewModel.search("limit")
        assertEquals(listOf("Limit laws", "Limits"), viewModel.shownNames, "at any depth, not outside")
        assertTrue(viewModel.canWidenSearch)

        viewModel.search("s")
        assertEquals(listOf("Sequences", "Limit laws", "Limits"), viewModel.shownNames, "the folders under it, not itself")

        viewModel.search("limit")
        viewModel.selectSearchScope(SearchScope.LIBRARY)
        assertEquals(listOf("Limit cycles", "Limit laws", "Limits"), viewModel.shownNames)

        // Leaving the search brings back its scope
        viewModel.navigateBack()
        assertEquals(SearchScope.HERE, viewModel.searchScope)
    }

    @Test
    fun aSearchInASectionLooksInItsNotes() = runTest {
        val viewModel = library()
        viewModel.showSection(Section.SHARED)
        viewModel.search("limit")
        assertTrue(viewModel.shownFiles.isEmpty())
        assertTrue(viewModel.canWidenSearch)
        viewModel.selectSearchScope(SearchScope.LIBRARY)
        assertEquals(listOf("Limit cycles", "Limit laws", "Limits"), viewModel.shownNames)
    }

    @Test
    fun theResultsOfTheContentSearchStayInTheScope() = runTest {
        val viewModel = library()
        val search = RecordingSearch()
        viewModel.contentSearch = search
        viewModel.search("limit")
        val cycles = viewModel.named("Limit cycles").id
        val limits = viewModel.named("Limits").id
        val laws = viewModel.named("Limit laws").id

        viewModel.showFolder(viewModel.folder("Analysis").id)
        viewModel.search("limit")
        search.found = listOf(cycles, limits, laws)
        assertEquals(listOf("Limits", "Limit laws"), viewModel.shownNames, "in the order found")
        viewModel.selectSearchScope(SearchScope.LIBRARY)
        assertEquals(listOf("Limit cycles", "Limits", "Limit laws"), viewModel.shownNames)
    }

    @Test
    fun theTrashIsSearchedByName() = runTest {
        val viewModel = library()
        viewModel.deleteFile(viewModel.named("Limit cycles"))
        viewModel.deleteFile(viewModel.named("Physics"))

        viewModel.showSection(Section.TRASH)
        assertEquals(2, viewModel.shownTrash.size)
        viewModel.search("phys")
        assertEquals(listOf("Physics"), viewModel.shownTrash.map { it.name })
        assertTrue(viewModel.shownFiles.isEmpty(), "never the notes of the library under its title")
        assertFalse(viewModel.canWidenSearch)
        viewModel.navigateBack()
        assertEquals(Section.TRASH, viewModel.section)
        assertEquals(2, viewModel.shownTrash.size)
    }

    @Test
    fun theSelectionOnlyHoldsWhatIsShown() = runTest {
        val viewModel = library()
        viewModel.toggleSelection(viewModel.named("Limit cycles"))

        // Hidden by a search, nothing acts on it
        viewModel.search("phys")
        assertTrue(viewModel.selectedItems.isEmpty())
        assertFalse(viewModel.selectionMode)
        viewModel.search("")

        viewModel.toggleSelection(viewModel.named("Physics"))
        viewModel.showFolder(viewModel.folder("Physics").id)
        assertTrue(viewModel.selectedItems.isEmpty())
        viewModel.navigateBack()
        assertTrue(viewModel.selectedItems.isEmpty(), "going somewhere leaves it for good")
    }

    @Test
    fun theSelectionFollowsTheItemsRenamed() = runTest {
        val viewModel = library()
        viewModel.toggleSelection(viewModel.named("Physics"))
        viewModel.renameFile(viewModel.named("Physics"), "Mechanics")
        assertEquals(listOf("Mechanics"), viewModel.selectedItems.map { it.name })
    }

    @Test
    fun theDetailsOfAnItemAreNotASelection() = runTest {
        val viewModel = library()
        val cycles = viewModel.named("Limit cycles")

        viewModel.showDetails(cycles)
        assertFalse(viewModel.selectionMode, "a click still opens what it is on")
        assertEquals(listOf("Limit cycles"), viewModel.targetItems.map { it.name }, "the commands act on it")
        assertTrue(viewModel.isSelected(cycles))
        assertTrue(viewModel.canNavigateBack)

        // A selection starts from it
        viewModel.toggleSelection(viewModel.named("Physics"))
        assertEquals(listOf("Limit cycles", "Physics"), viewModel.selectedItems.map { it.name })
        assertNull(viewModel.detailsItem)

        viewModel.clearSelection()
        viewModel.showDetails(cycles)
        viewModel.enterFolder(viewModel.named("Physics"))
        assertNull(viewModel.detailsItem)
        assertEquals("Physics", viewModel.currentFolder?.name)
    }
}
