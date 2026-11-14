package io.github.valerioisufi.drawview.explorer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel.Section
import io.github.valerioisufi.drawview.explorer.ui.ExplorerScreen
import io.github.valerioisufi.drawview.explorer.ui.rememberLibraryGridState
import io.github.valerioisufi.drawview.session.EditorSessions
import io.github.valerioisufi.drawview.store.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.store.KeyValueDocumentStore
import io.github.valerioisufi.drawview.store.OpenDocuments
import io.github.valerioisufi.drawview.ui.adaptive.AdaptiveRoot
import io.github.valerioisufi.drawview.ui.adaptive.InputHints
import io.github.valerioisufi.drawview.ui.adaptive.ShortcutModifier
import io.github.valerioisufi.drawview.ui.adaptive.platformConventions
import io.github.valerioisufi.drawview.ui.command.CommandRoot
import io.github.valerioisufi.drawview.ui.theme.DrawViewTheme
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first

/** Moving through the library during a search, on the interface (docs/library-search.md). */
@OptIn(ExperimentalTestApi::class)
class LibrarySearchNavigationTest {

    @BeforeTest
    fun setUp() {
        Locale.setDefault(Locale.US)
    }

    /** A wide library: `Analysis` (`Limits`), `Physics` (`Motion`, `Waves`), `Limit cycles`. */
    private fun library(test: suspend DesktopComposeUiTest.(FileExplorerViewModel) -> Unit) = runDesktopComposeUiTest(1440, 900) {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage())
        store.createFolder("Analysis", parentId = null)
        store.createFolder("Physics", parentId = null)
        val folders = store.folders.first()
        store.createDocument("Limits", folderId = folders.first { it.name == "Analysis" }.id)
        store.createDocument("Motion", folderId = folders.first { it.name == "Physics" }.id)
        store.createDocument("Waves", folderId = folders.first { it.name == "Physics" }.id)
        store.createDocument("Limit cycles")
        val viewModels = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides viewModels) {
                DrawViewTheme(dynamicColor = false) {
                    AdaptiveRoot(hints = InputHints.PointerAndKeyboard, conventions = platformConventions) {
                        CommandRoot {
                            DocumentLibrary(
                                title = { Text("DrawView") },
                                documents = remember { OpenDocuments(store, dispatcher = Dispatchers.Unconfined) },
                                sessions = remember { EditorSessions() },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
        waitForIdle()
        test(viewModels.viewModelStore.keys().firstNotNullOf { viewModels.viewModelStore[it] as? FileExplorerViewModel })
        viewModels.viewModelStore.clear()
    }

    private fun DesktopComposeUiTest.search(text: String) {
        onNode(hasSetTextAction()).performTextInput(text)
        waitForIdle()
    }

    private fun DesktopComposeUiTest.click(text: String, index: Int = 0) {
        onAllNodesWithText(text)[index].performClick()
        waitForIdle()
    }

    @Test
    fun theSectionsOfThePaneLeaveTheSearch() = library { viewModel ->
        for ((label, section) in listOf("Recent" to Section.RECENT, "Shared" to Section.SHARED, "Trash" to Section.TRASH)) {
            viewModel.showFolder(null)
            waitForIdle()
            search("limit")
            click(label)
            assertEquals(section, viewModel.section)
            assertEquals("", viewModel.searchQuery, label)
            assertEquals(0, onAllNodesWithText("Limit cycles").fetchSemanticsNodes().size, label)
        }
    }

    @Test
    fun thereIsNoWayUpDuringASearch() = library { viewModel ->
        viewModel.showFolder(viewModel.rootFolders.first { it.name == "Physics" }.id)
        waitForIdle()
        assertEquals(1, onAllNodesWithContentDescription("Navigate up").fetchSemanticsNodes().size)
        search("mot")
        assertEquals(0, onAllNodesWithContentDescription("Navigate up").fetchSemanticsNodes().size)
    }

    @Test
    fun aSearchInAFolderCanLookEverywhere() = library { viewModel ->
        viewModel.showFolder(viewModel.rootFolders.first { it.name == "Physics" }.id)
        waitForIdle()
        search("limit")
        assertEquals(0, onAllNodesWithText("Limit cycles").fetchSemanticsNodes().size)
        onNodeWithText("In Physics").assertExists()

        click("Whole library")
        assertEquals(1, onAllNodesWithText("Limit cycles").fetchSemanticsNodes().size)
    }

    @Test
    fun theResultsSayWhatTheyAreAndWhereTheyLooked() = library { viewModel ->
        viewModel.showFolder(viewModel.rootFolders.first { it.name == "Analysis" }.id)
        waitForIdle()
        search("limit")
        onNodeWithText("Results for “limit”").assertExists()
        onNodeWithText("1 item · In Analysis", substring = false).assertExists()
        // The folder of the result, in place of its date
        assertEquals(2, onAllNodesWithText("Analysis").fetchSemanticsNodes().size, "in the pane and under the note")

        click("Whole library")
        onNodeWithText("2 items · Whole library").assertExists()
    }

    @Test
    fun aSearchWithoutResultsHereOffersTheWholeLibrary() = library { viewModel ->
        viewModel.showFolder(viewModel.rootFolders.first { it.name == "Physics" }.id)
        waitForIdle()
        search("limit")
        onNodeWithText("No notes match “limit”").assertExists()
        // The chip and the button of the empty state
        click("Whole library", index = 1)
        assertEquals(1, onAllNodesWithText("Limit cycles").fetchSemanticsNodes().size)
    }

    @Test
    fun aClickOpensAFolderWithTheDetailsOfANoteOpen() = library { viewModel ->
        viewModel.search("i")
        viewModel.showDetails(viewModel.shownFiles.first { it.name == "Motion" })
        waitForIdle()
        // In the pane, then among the results, then as the folder of the note in the details
        click("Physics", index = 1)
        assertEquals("Physics", viewModel.currentFolder?.name)
        assertEquals("", viewModel.searchQuery)
        assertNull(viewModel.detailsItem)
        assertTrue(viewModel.selectedItems.isEmpty())
    }

    /** The library without thumbnails (a list), whose position the test can read and change. */
    private fun longList(test: suspend DesktopComposeUiTest.(FileExplorerViewModel, LazyGridState) -> Unit) = runDesktopComposeUiTest(400, 800) {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage())
        store.createFolder("Physics", parentId = null)
        repeat(80) { store.createDocument("Note ${it + 10}") }
        val viewModels = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        var gridState: LazyGridState? = null
        setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides viewModels) {
                DrawViewTheme(dynamicColor = false) {
                    AdaptiveRoot(hints = InputHints.PointerAndKeyboard, conventions = platformConventions) {
                        CommandRoot {
                            val documents = remember { OpenDocuments(store, dispatcher = Dispatchers.Unconfined) }
                            val viewModel = viewModel { FileExplorerViewModel(documents) }
                            val state = rememberLibraryGridState(viewModel)
                            gridState = state
                            ExplorerScreen(viewModel, onOpenDocument = {}, title = { Text("DrawView") }, gridState = state, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
        waitForIdle()
        test(viewModels.viewModelStore.keys().firstNotNullOf { viewModels.viewModelStore[it] as? FileExplorerViewModel }, gridState!!)
        viewModels.viewModelStore.clear()
    }

    private val primaryKey = if (platformConventions.shortcutModifier == ShortcutModifier.COMMAND) Key.MetaLeft else Key.CtrlLeft

    @Test
    fun theSearchStaysWithinReachWhereverTheListIs() = longList { _, gridState ->
        gridState.requestScrollToItem(60)
        waitForIdle()
        assertTrue(gridState.firstVisibleItemIndex > 0)
        onNode(hasSetTextAction()).assertExists()

        onRoot().performKeyInput { withKeyDown(primaryKey) { pressKey(Key.K) } }
        waitForIdle()
        onNode(hasSetTextAction() and isFocused()).assertExists()
    }

    @Test
    fun whatIsShownStartsFromTheTop() = longList { viewModel, gridState ->
        gridState.requestScrollToItem(60)
        waitForIdle()
        search("note")
        assertEquals(0, gridState.firstVisibleItemIndex, "the results")

        gridState.requestScrollToItem(30)
        waitForIdle()
        viewModel.search("")
        viewModel.showFolder(viewModel.rootFolders.single().id)
        waitForIdle()
        assertEquals(0, gridState.firstVisibleItemIndex, "a folder")
    }

    @Test
    fun oneBackLeavesTheSearchWithTheFocusInIt() = library { viewModel ->
        viewModel.showFolder(viewModel.rootFolders.first { it.name == "Physics" }.id)
        waitForIdle()
        search("mot")
        onNode(hasSetTextAction() and isFocused()).assertExists()

        onRoot().performKeyInput { pressKey(Key.Back) }
        waitForIdle()
        assertEquals("", viewModel.searchQuery)
        assertEquals("Physics", viewModel.currentFolder?.name)
        assertEquals(0, onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().size)

        onRoot().performKeyInput { pressKey(Key.Back) }
        waitForIdle()
        assertNull(viewModel.currentFolder, "the next one leaves the folder")
    }

    @Test
    fun goingSomewhereTakesTheFocusFromTheSearch() = library { viewModel ->
        search("phys")
        onNode(hasSetTextAction() and isFocused()).assertExists()
        onAllNodesWithText("Physics")[1].performClick()
        waitForIdle()
        assertEquals("Physics", viewModel.currentFolder?.name)
        assertEquals(0, onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().size)
    }

    @Test
    fun theTrashShowsOnlyWhatItHolds() = library { viewModel ->
        viewModel.deleteFiles(listOf(viewModel.shownFiles.first { it.name == "Limit cycles" }))
        viewModel.showSection(Section.TRASH)
        waitForIdle()
        search("xyz")
        onAllNodesWithText("No notes match “xyz”").onFirst().assertExists()
        assertEquals(0, onAllNodesWithText("Limits").fetchSemanticsNodes().size)
    }
}
