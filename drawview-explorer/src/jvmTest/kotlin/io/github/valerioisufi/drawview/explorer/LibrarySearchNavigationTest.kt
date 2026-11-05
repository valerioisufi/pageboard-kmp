package io.github.valerioisufi.drawview.explorer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel.Section
import io.github.valerioisufi.drawview.store.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.store.KeyValueDocumentStore
import io.github.valerioisufi.drawview.store.OpenDocuments
import io.github.valerioisufi.drawview.ui.adaptive.AdaptiveRoot
import io.github.valerioisufi.drawview.ui.adaptive.InputHints
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

    @Test
    fun theTrashShowsOnlyWhatItHolds() = library { viewModel ->
        viewModel.deleteFile(viewModel.shownFiles.first { it.name == "Limit cycles" })
        viewModel.showSection(Section.TRASH)
        waitForIdle()
        search("xyz")
        onAllNodesWithText("No notes match “xyz”").onFirst().assertExists()
        assertEquals(0, onAllNodesWithText("Limits").fetchSemanticsNodes().size)
    }
}
