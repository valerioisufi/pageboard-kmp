package io.github.valerioisufi.pageboard.search.ui

import io.github.valerioisufi.pageboard.handwriting.HandwritingEngines
import io.github.valerioisufi.pageboard.handwriting.HandwritingReading
import io.github.valerioisufi.pageboard.handwriting.InkLanguageModel
import io.github.valerioisufi.pageboard.handwriting.InkLanguageModels
import io.github.valerioisufi.pageboard.handwriting.InkReading
import io.github.valerioisufi.pageboard.handwriting.InkSample
import io.github.valerioisufi.pageboard.handwriting.TextInkRecognizer
import io.github.valerioisufi.pageboard.search.HandwritingSearch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.takahirom.roborazzi.captureRoboImage
import io.github.valerioisufi.pageboard.explorer.DocumentOpener
import io.github.valerioisufi.pageboard.explorer.LibrarySearchMatch
import io.github.valerioisufi.pageboard.ml.InstalledModels
import io.github.valerioisufi.pageboard.ml.ModelCatalog
import io.github.valerioisufi.pageboard.ml.ModelDownloads
import io.github.valerioisufi.pageboard.search.CitedPassage
import io.github.valerioisufi.pageboard.search.NotesAnswer
import io.github.valerioisufi.pageboard.search.OnlineAnswerBackend
import io.github.valerioisufi.pageboard.search.SearchAnchor
import io.github.valerioisufi.pageboard.search.SearchChunk
import io.github.valerioisufi.pageboard.search.SearchChunkKind
import io.github.valerioisufi.pageboard.search.SearchEngine
import io.github.valerioisufi.pageboard.search.SearchHit
import io.github.valerioisufi.pageboard.search.SearchIndex
import io.github.valerioisufi.pageboard.search.SearchIndexer
import io.github.valerioisufi.pageboard.search.SearchSource
import io.github.valerioisufi.pageboard.store.DocumentSummary
import io.github.valerioisufi.pageboard.store.InMemoryKeyValueStorage
import io.github.valerioisufi.pageboard.store.KeyValueDocumentStore
import io.github.valerioisufi.pageboard.ui.theme.PageboardTheme
import io.github.valerioisufi.pageboard.ui.theme.LocalCanvasColors
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Screenshots of the search results (with snippets, places and icons) and of the search models
 * settings dialog (`docs/plans/search.md`).
 *
 * `./gradlew :pageboard-search-ui:recordRoborazziJvm` records them in `screenshots/`.
 */
@OptIn(ExperimentalTestApi::class)
class SearchScreenshotTest {
    private val tempDir: File = Files.createTempDirectory("search-screenshots").toFile()

    @BeforeTest
    fun setUp() {
        Locale.setDefault(Locale.US)
    }

    @AfterTest
    fun cleanUp() {
        tempDir.deleteRecursively()
    }

    private fun capture(
        name: String,
        width: Int,
        height: Int,
        dark: Boolean = false,
        content: @Composable () -> Unit
    ) = runDesktopComposeUiTest(width = width, height = height) {
        setContent {
            PageboardTheme(darkTheme = dark, dynamicColor = false) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LocalCanvasColors.current.desk)
                        .padding(24.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Surface(
                        modifier = Modifier.width((width - 48).dp),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        content()
                    }
                }
            }
        }
        waitForIdle()
        captureToImage().captureRoboImage("screenshots/$name.png")
    }

    @Test
    fun searchResultsMatches() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = scope)
        val doc = runBlocking { store.createDocument("Analisi Matematica I") }
        SearchIndex(SearchIndex.MEMORY_PATH).use { index ->
            val multiKindSource = object : SearchSource {
                override val id: String = "test"
                override suspend fun version(document: DocumentSummary): String = "v1"
                override suspend fun chunks(document: DocumentSummary): List<SearchChunk> = listOf(
                    SearchChunk(
                        sourceId = id,
                        anchor = SearchAnchor.Page(document.id, "p1", pageNumber = 3),
                        kind = SearchChunkKind.TEXT,
                        title = document.name,
                        // Its formula is shown as plain math, not as its LaTeX
                        text = "Teorema di Weierstrass: se \$f\$ è continua su un compatto \$K\$, allora \$\\exists\\, x_0 \\in K : f(x) \\le f(x_0)\$ per ogni \$x \\in K\$."
                    ),
                    SearchChunk(
                        sourceId = id,
                        anchor = SearchAnchor.Page(document.id, "p2", pageNumber = 7),
                        kind = SearchChunkKind.PDF,
                        title = document.name,
                        text = "Dimostrazione del teorema di Weierstrass mediante successioni estratte convergenti."
                    ),
                    SearchChunk(
                        sourceId = id,
                        anchor = SearchAnchor.Lecture(document.id, "lec-1", startMs = 750_000L),
                        kind = SearchChunkKind.LECTURE_TRANSCRIPT,
                        title = document.name,
                        text = "Adesso vediamo perché l'ipotesi di compattezza nel teorema di Weierstrass non può essere rimossa."
                    )
                )
            }
            val indexer = SearchIndexer(catalog = store, index = index, sources = listOf(multiKindSource), quietMillis = 0L)
            val engine = SearchEngine(index)
            val search = ContentSearch(index = index, indexer = indexer, engine = engine)
            runBlocking {
                indexer.indexNow()
                search.onQueryChange("Weierstrass")
                search.searchNow()
            }
            val matches = search.matchesOf(doc.id)
            for (dark in listOf(false, true)) {
                capture(
                    name = "search-results-${if (dark) "dark" else "light"}",
                    width = 560,
                    height = 280,
                    dark = dark
                ) {
                    SearchResultCardPreview(title = doc.title, matches = matches)
                }
            }
        }
    }

    @Test
    fun searchSettingsDialog() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = scope)
        val modelsDir = File(tempDir, "models").apply { mkdirs() }
        // Simulate EmbeddingGemma 2 installed
        val embSpec = ModelCatalog.EMBEDDING_GEMMA_2_TEXT
        val modelFile = File(modelsDir, embSpec.id).apply { mkdirs() }.resolve(embSpec.fileName)
        java.io.RandomAccessFile(modelFile, "rw").use { it.setLength(embSpec.sizeBytes) }
        val installedModels = InstalledModels(directory = modelsDir.absolutePath)
        val downloads = ModelDownloads(installedModels, scope = scope)

        SearchIndex(SearchIndex.MEMORY_PATH).use { index ->
            val indexer = SearchIndexer(catalog = store, index = index, sources = emptyList(), quietMillis = 0L)
            val engine = SearchEngine(index)
            val search = ContentSearch(
                index = index,
                indexer = indexer,
                engine = engine,
                installedModels = installedModels,
                downloads = downloads,
                runtimeAvailable = true
            )
            // The dialog as the application opens it, and in a narrow desktop window: the title and
            // the close button in sight above the settings that scroll
            for ((name, size) in listOf("light" to (628 to 840), "dark" to (628 to 840), "narrow" to (360 to 480))) {
                val dark = name == "dark"
                runDesktopComposeUiTest(width = size.first, height = size.second) {
                    setContent {
                        PageboardTheme(darkTheme = dark, dynamicColor = false) {
                            Box(modifier = Modifier.fillMaxSize().background(LocalCanvasColors.current.desk))
                            SearchSettingsDialog(search = search, onDismiss = {})
                        }
                    }
                    waitForIdle()
                    captureToImage().captureRoboImage("screenshots/search-settings-$name.png")
                }
            }
        }
    }

    @Test
    fun searchSettingsWithHandwriting() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = scope)
        // A reader of Italian whose model is downloaded on request (ML Kit), not downloaded yet
        val reader = object : TextInkRecognizer {
            override val version: String = "fake 1"
            override suspend fun languages(): Set<String> = setOf("it")
            override suspend fun recognize(ink: InkSample, language: String, context: String): InkReading? = null
        }
        val models = object : InkLanguageModels {
            override suspend fun models(languages: Collection<String>) = languages.map { InkLanguageModel(it, isDownloaded = false, sizeBytes = 20_000_000L) }
            override suspend fun download(language: String, wifiOnly: Boolean) = Unit
            override suspend fun delete(language: String) = Unit
        }
        val handwriting = HandwritingSearch(HandwritingReading(HandwritingEngines(listOf(reader), languageModels = models)), language = "it")
        SearchIndex(SearchIndex.MEMORY_PATH).use { index ->
            val indexer = SearchIndexer(catalog = store, index = index, sources = emptyList(), quietMillis = 0L)
            val search = ContentSearch(index = index, indexer = indexer, engine = SearchEngine(index), handwriting = handwriting)
            for (dark in listOf(false, true)) {
                runDesktopComposeUiTest(width = 628, height = 1000) {
                    setContent {
                        PageboardTheme(darkTheme = dark, dynamicColor = false) {
                            Box(modifier = Modifier.fillMaxSize().background(LocalCanvasColors.current.desk))
                            SearchSettingsDialog(search = search, onDismiss = {})
                        }
                    }
                    waitForIdle()
                    captureToImage().captureRoboImage("screenshots/search-settings-handwriting-${if (dark) "dark" else "light"}.png")
                }
            }
        }
    }

    @Test
    fun askYourNotesCard() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = scope)
        val modelsDir = File(tempDir, "models-ask").apply { mkdirs() }
        val gemmaSpec = ModelCatalog.GEMMA_4_E2B
        val modelFile = File(modelsDir, gemmaSpec.id).apply { mkdirs() }.resolve(gemmaSpec.fileName)
        java.io.RandomAccessFile(modelFile, "rw").use { it.setLength(gemmaSpec.sizeBytes) }
        val installedModels = InstalledModels(directory = modelsDir.absolutePath)

        SearchIndex(SearchIndex.MEMORY_PATH).use { index ->
            val indexer = SearchIndexer(catalog = store, index = index, sources = emptyList(), quietMillis = 0L)
            val engine = SearchEngine(index)
            val onlineBackend = OnlineAnswerBackend(
                isAvailable = { true },
                generate = { _, _ -> "" to "Gemini" }
            )
            val search = ContentSearch(
                index = index,
                indexer = indexer,
                engine = engine,
                installedModels = installedModels,
                runtimeAvailable = true,
                onlineBackend = onlineBackend
            )
            val hit1 = SearchHit(
                anchor = SearchAnchor.Page("doc-1", "p1", pageNumber = 3),
                kind = SearchChunkKind.TEXT,
                title = "Analisi Matematica I",
                text = "Il teorema di Weierstrass garantisce che ogni funzione continua su un compatto ammette massimo e minimo assoluti.",
                snippet = "Il teorema di Weierstrass garantisce che ogni funzione continua su un compatto ammette massimo e minimo assoluti.",
                score = 0.9
            )
            val hit2 = SearchHit(
                anchor = SearchAnchor.Lecture("doc-1", "lec-1", startMs = 750_000L),
                kind = SearchChunkKind.LECTURE_TRANSCRIPT,
                title = "Analisi Matematica I",
                text = "L'ipotesi di compattezza (insieme chiuso e limitato in R^n) non può essere rimossa.",
                snippet = "L'ipotesi di compattezza (insieme chiuso e limitato in R^n) non può essere rimossa.",
                score = 0.85
            )
            search.currentAnswer = NotesAnswer(
                question = "Quali sono le ipotesi del teorema di Weierstrass?",
                text = "Il **teorema di Weierstrass** richiede che la funzione sia continua e che il dominio sia un insieme **compatto** (chiuso e limitato in $\\mathbb{R}^n$) [1]. Senza la compattezza l'esistenza di massimo e minimo assoluti non è garantita [2].",
                citations = listOf(CitedPassage(1, hit1), CitedPassage(2, hit2)),
                passages = listOf(hit1, hit2),
                modelName = ModelCatalog.GEMMA_4_E2B.name
            )
            for (dark in listOf(false, true)) {
                capture(
                    name = "search-ask-${if (dark) "dark" else "light"}",
                    width = 620,
                    height = 300,
                    dark = dark
                ) {
                    Box(modifier = Modifier.padding(16.dp)) {
                        AskNotesCard(
                            search = search,
                            query = "Quali sono le ipotesi del teorema di Weierstrass?",
                            openDocument = DocumentOpener { _, _ -> }
                        )
                    }
                }
            }

            // An answer that cites nothing: the passages searched, quieter than citations
            search.currentAnswer = NotesAnswer(
                question = "Chi ha dimostrato il teorema di Weierstrass?",
                text = "Gli appunti non dicono chi abbia dimostrato il teorema.",
                citations = emptyList(),
                passages = listOf(hit1, hit2),
                modelName = ModelCatalog.GEMMA_4_E2B.name
            )
            for (dark in listOf(false, true)) {
                capture(
                    name = "search-ask-uncited-${if (dark) "dark" else "light"}",
                    width = 620,
                    height = 240,
                    dark = dark
                ) {
                    Box(modifier = Modifier.padding(16.dp)) {
                        AskNotesCard(
                            search = search,
                            query = "Chi ha dimostrato il teorema di Weierstrass?",
                            openDocument = DocumentOpener { _, _ -> }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultCardPreview(
    title: String,
    matches: List<LibrarySearchMatch>
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface
        )
        for (match in matches) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceContainerHighest.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                match.icon?.let { icon ->
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
                if (match.location.isNotEmpty()) {
                    Text(
                        text = match.location,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.primary,
                        maxLines = 1
                    )
                }
                Text(
                    text = match.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
