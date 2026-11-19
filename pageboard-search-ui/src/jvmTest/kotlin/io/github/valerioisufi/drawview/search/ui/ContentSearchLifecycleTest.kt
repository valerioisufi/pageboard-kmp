package io.github.valerioisufi.drawview.search.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.github.valerioisufi.drawview.search.SearchChunk
import io.github.valerioisufi.drawview.search.SearchIndex
import io.github.valerioisufi.drawview.search.SearchSource
import io.github.valerioisufi.drawview.store.DocumentSummary
import io.github.valerioisufi.drawview.store.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.store.KeyValueDocumentStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The search a composition remembers stops its work when it is replaced or leaves; the one an
 * application shares works in the scope it is started in.
 */
@OptIn(ExperimentalTestApi::class)
class ContentSearchLifecycleTest {

    /** A source without content that counts who listens to its changes (one per indexer running). */
    private class CountingSource : SearchSource {
        val changesFlow = MutableSharedFlow<Unit>()
        override val id: String = "counting"
        override val changes: Flow<Unit> get() = changesFlow
        override suspend fun version(document: DocumentSummary): String? = null
        override suspend fun chunks(document: DocumentSummary): List<SearchChunk> = emptyList()
    }

    @Test
    fun aSearchReplacedStopsItsIndexer() = runDesktopComposeUiTest {
        val storeScope = CoroutineScope(SupervisorJob())
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = storeScope)
        val source = CountingSource()
        val sources = listOf<SearchSource>(source)
        var runtimeAvailable by mutableStateOf(false)
        var shown by mutableStateOf(true)
        setContent {
            if (shown) {
                rememberContentSearch(
                    store = store,
                    extraSources = sources,
                    indexPath = SearchIndex.MEMORY_PATH,
                    runtimeAvailable = runtimeAvailable
                )
            }
        }
        waitForIdle()
        assertEquals(1, source.changesFlow.subscriptionCount.value)

        // Another search for another key: the first one stops
        runtimeAvailable = true
        waitForIdle()
        assertEquals(1, source.changesFlow.subscriptionCount.value)

        shown = false
        waitForIdle()
        assertEquals(0, source.changesFlow.subscriptionCount.value)
        storeScope.cancel()
    }

    @Test
    fun aSharedSearchWorksInTheScopeItIsStartedIn() = runBlocking {
        val storeScope = CoroutineScope(SupervisorJob())
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = storeScope)
        val source = CountingSource()
        val search = createContentSearch(store, extraSources = listOf(source), indexPath = SearchIndex.MEMORY_PATH)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        search.start(scope)
        withTimeout(5_000) { source.changesFlow.subscriptionCount.first { it == 1 } }

        scope.coroutineContext.job.cancelAndJoin()
        search.close()
        assertEquals(0, source.changesFlow.subscriptionCount.value)
        storeScope.cancel()
    }
}
