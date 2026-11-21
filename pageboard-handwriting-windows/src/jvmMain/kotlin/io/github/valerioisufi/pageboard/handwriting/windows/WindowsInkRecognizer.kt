package io.github.valerioisufi.pageboard.handwriting.windows

import io.github.valerioisufi.pageboard.handwriting.InkReading
import io.github.valerioisufi.pageboard.handwriting.InkSample
import io.github.valerioisufi.pageboard.handwriting.TextInkRecognizer
import io.github.valerioisufi.pageboard.platform.JvmNativeLibraries
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The handwriting recognizers of Windows as a reader of text: a line of samples read by the
 * recognizer of its language (one of those Windows installs with its languages), with the
 * candidates of each word. On another system, or without the library, it reads no language.
 *
 * The recognizers are named in the language of Windows ("Microsoft English (US) Handwriting
 * Recognizer", "Riconoscimento grafia italiana Microsoft"): a language is matched by the first
 * letters of its name in English, in itself and in the language of the system.
 */
public class WindowsInkRecognizer : TextInkRecognizer {
    override val version: String = "$WINDOWS_READER ${System.getProperty("os.version").orEmpty()}"

    override suspend fun languages(): Set<String> = withContext(dispatcher) {
        val names = recognizerNames()
        LANGUAGES.filterTo(mutableSetOf()) { language -> recognizerOf(language, names) != null }
    }

    override suspend fun recognize(ink: InkSample, language: String, context: String): InkReading? = withContext(dispatcher) {
        if (ink.isEmpty) return@withContext null
        val name = recognizerOf(language, recognizerNames()) ?: return@withContext null
        val points = ink.strokes.flatten()
        val xy = FloatArray(points.size * 2)
        val times = LongArray(points.size)
        points.forEachIndexed { i, p ->
            xy[i * 2] = p.x * DIPS_PER_MM
            xy[i * 2 + 1] = p.y * DIPS_PER_MM
            times[i] = p.timestampMs * 1000
        }
        val words = WindowsInkBridge.read(name, xy, ink.strokes.map { it.size }.toIntArray(), times) ?: return@withContext null
        val candidates = words.map { word -> word.split('\n').filter { it.isNotBlank() } }.filter { it.isNotEmpty() }
        if (candidates.isEmpty()) return@withContext null
        InkReading(
            text = candidates.joinToString(" ") { it.first() },
            searchText = candidates.joinToString(" ") { it.distinct().joinToString(" ") }
        )
    }

    private fun recognizerNames(): List<String> = if (WindowsInkBridge.isLoaded) WindowsInkBridge.names().orEmpty() else emptyList()

    private fun recognizerOf(language: String, names: List<String>): String? {
        val locale = Locale.forLanguageTag(language)
        val stems = listOf(locale.getDisplayLanguage(Locale.ENGLISH), locale.getDisplayLanguage(locale), locale.getDisplayLanguage(Locale.getDefault()))
            .filter { it.length >= 3 }
            .map { it.take(STEM).lowercase(Locale.ROOT) }
            .distinct()
        val matching = names.filter { name -> stems.any { name.lowercase(Locale.ROOT).contains(it) } }
        // The region of the language when Windows has several (English (US), (UK)...)
        val region = locale.country.takeIf { it.isNotEmpty() } ?: DEFAULT_REGIONS[locale.language]
        return matching.firstOrNull { region != null && it.contains("($region)") } ?: matching.firstOrNull()
    }

    private companion object {
        const val DIPS_PER_MM = 96f / 25.4f
        const val STEM = 5

        /** The languages looked for among the recognizers of Windows. */
        val LANGUAGES = listOf("it", "en", "fr", "de", "es", "pt", "nl", "pl", "sv", "da", "nb", "fi", "cs", "ru", "ja", "zh", "ko")

        val DEFAULT_REGIONS = mapOf("en" to "US", "pt" to "BR")

        /** The ink of Windows reads on one thread of its own, a single-threaded apartment. */
        val dispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "windows-ink").apply { isDaemon = true } }
            .asCoroutineDispatcher()
    }
}

/** The JNI library of the ink of Windows (`native/bridge/ink_recognizer_windows.cpp`). */
internal object WindowsInkBridge {
    val isLoaded: Boolean by lazy {
        System.getProperty("os.name").orEmpty().startsWith("Windows") &&
            JvmNativeLibraries.load(WindowsInkBridge::class.java, "ink_recognizer_bridge")
    }

    fun names(): List<String>? = recognizers()?.toList()

    fun read(name: String, xy: FloatArray, sizes: IntArray, times: LongArray): List<String>? =
        if (isLoaded) recognize(name, xy, sizes, times)?.toList() else null

    @JvmStatic
    private external fun recognizers(): Array<String>?

    @JvmStatic
    private external fun recognize(name: String, xy: FloatArray, sizes: IntArray, times: LongArray): Array<String>?
}
