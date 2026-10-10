package io.github.valerioisufi.drawview.explorer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import io.github.valerioisufi.drawview.data.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.data.KeyValueDocumentStore
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel.FileType
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel.Section
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.ui.components.BadgeTone
import io.github.valerioisufi.drawview.ui.session.DocumentTag
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** What the library shows (docs/ui-design.md): the sections, the search, the filters, the counts, the notes made. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.library(): Pair<KeyValueDocumentStore, FileExplorerViewModel> {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = backgroundScope)
        return store to FileExplorerViewModel(store)
    }

    private val FileExplorerViewModel.shownNames get() = shownFiles.map { it.name }

    @Test
    fun searchesTheWholeLibraryByName() = runTest {
        val (store, viewModel) = library()
        store.createFolder("Analysis", parentId = null)
        val analysis = viewModel.rootFolders.single()
        store.createDocument("Limits", folderId = analysis.id)
        store.createDocument("Derivatives")
        store.createDocument("Limit cycles")

        viewModel.search("limit")
        assertEquals(listOf("Limit cycles", "Limits"), viewModel.shownNames)
        assertTrue(viewModel.canNavigateBack)

        // A folder found is shown in its place, the search left
        viewModel.search("anal")
        viewModel.enterFolder(viewModel.shownFiles.single())
        assertEquals("", viewModel.searchQuery)
        assertEquals(listOf("Limits"), viewModel.shownNames)

        viewModel.navigateBack()
        viewModel.search("nothing")
        assertTrue(viewModel.shownFiles.isEmpty())
        viewModel.navigateBack()
        assertEquals("", viewModel.searchQuery)
    }

    @Test
    fun filtersTheNotesByKindAndByTag() = runTest {
        val (store, viewModel) = library()
        store.createFolder("Analysis", parentId = null)
        val lecture = store.createDocument("Lecture")
        store.createDocument("Exercises")
        val lectures = setOf(lecture.id)
        viewModel.tags = listOf(DocumentTag("lecture", { "Lecture" }, { "Lectures" }, Icons.Outlined.Mic, BadgeTone.Blue) { lectures })

        assertEquals(listOf("Analysis", "Exercises", "Lecture"), viewModel.shownNames)
        assertEquals(listOf("pdf", "lecture", "shared"), viewModel.allTags.map { it.id })

        viewModel.selectFilter(LibraryFilter.Notes)
        assertEquals(listOf("Exercises", "Lecture"), viewModel.shownNames)
        viewModel.selectFilter(LibraryFilter.Tagged("lecture"))
        assertEquals(listOf("Lecture"), viewModel.shownNames)
        assertEquals(listOf("lecture"), viewModel.tagsOf(viewModel.shownFiles.single()).map { it.id })
        viewModel.selectFilter(LibraryFilter.Tagged("pdf"))
        assertTrue(viewModel.shownFiles.isEmpty())
        viewModel.selectFilter(LibraryFilter.All)
        assertEquals(3, viewModel.shownFiles.size)
    }

    @Test
    fun showsTheSectionsWithTheirCounts() = runTest {
        val (store, viewModel) = library()
        store.createFolder("Analysis", parentId = null)
        val analysis = viewModel.rootFolders.single()
        store.createDocument("Limits", folderId = analysis.id)
        store.createDocument("Series", folderId = analysis.id)
        store.createDocument("Home note")

        assertEquals(1, viewModel.homeNoteCount)
        assertEquals(2, viewModel.rootFolders.single().noteCount)
        assertEquals(0, viewModel.sharedNoteCount)

        viewModel.showSection(Section.SHARED)
        assertTrue(viewModel.shownFiles.isEmpty())
        assertTrue(viewModel.canNavigateBack)
        viewModel.navigateBack()
        assertEquals(Section.HOME, viewModel.section)
        assertFalse(viewModel.canNavigateBack)

        viewModel.showFolder(analysis.id)
        assertEquals(listOf("Analysis"), viewModel.currentPath.map { it.name })
        assertEquals("Analysis", viewModel.folderName(viewModel.shownFiles.first().folderId))
    }

    @Test
    fun makesANoteOnThePaperChosenInTheFolderChosen() = runTest {
        val (store, viewModel) = library()
        store.createFolder("Analysis", parentId = null)
        val analysis = viewModel.rootFolders.single()
        var created: String? = null
        viewModel.createNote("Limits", PageBackground.Grid(), analysis.id, onCreated = { created = it })

        val note = checkNotNull(created)
        val summary = viewModel.run { showFolder(analysis.id); shownFiles.single() }
        assertEquals(note, summary.id)
        assertEquals(FileType.FILE, summary.type)
        assertEquals(1, summary.pageCount)
        assertTrue(store.loadDocument(note)?.defaultBackground is PageBackground.Grid)
        // The name is taken in that folder, free in the root
        assertTrue(viewModel.validateNoteName("Limits", analysis.id) != null)
        assertEquals(null, viewModel.validateNoteName("Limits", null))
        assertEquals("Limits (2)", viewModel.availableNoteName("Limits", analysis.id))
    }
}
