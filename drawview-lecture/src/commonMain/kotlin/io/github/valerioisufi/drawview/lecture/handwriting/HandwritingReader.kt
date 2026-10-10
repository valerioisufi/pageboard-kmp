package io.github.valerioisufi.drawview.lecture.handwriting

import io.github.valerioisufi.drawview.platform.PlatformFiles
import io.github.valerioisufi.drawview.lecture.LectureAnalysis
import io.github.valerioisufi.drawview.lecture.ModelProvider
import io.github.valerioisufi.drawview.lecture.NotebookSnapshot
import io.github.valerioisufi.drawview.lecture.NotebookSource
import io.github.valerioisufi.drawview.lecture.PageContent
import io.github.valerioisufi.drawview.lecture.TaskModel
import io.github.valerioisufi.drawview.lecture.TokenUsage
import io.github.valerioisufi.drawview.lecture.writeAtomically
import io.github.valerioisufi.drawview.lecture.model.ModelBackends
import io.github.valerioisufi.drawview.lecture.model.ModelPrompt
import io.github.valerioisufi.drawview.lecture.model.OllamaBackend
import io.github.valerioisufi.drawview.lecture.model.PromptPart
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.concurrent.Volatile

/**
 * Transcribes the handwriting of a notebook once: every block of strokes ([InkBlock]) is sent as
 * an image to the model of the live suggestions, and its text is kept by key in [path]
 * (`handwriting.json` of the document, shared by its lectures). Adding or erasing a stroke changes
 * the key of its block, which is transcribed again.
 *
 * The devices working on the notebook share what they read ([merge]): the file gathers it when
 * saved ([onSaved]).
 *
 * One per notebook, shared by its recording and its reviews: one transcription at a time, the
 * texts read from any coroutine.
 */
internal class HandwritingReader(
    private val path: String,
    private val backends: ModelBackends,
    private val onSaved: () -> Unit = {}
) {
    private val store = atomic<Map<String, Entry>?>(null)
    private val mutex = Mutex()

    /** The texts by key of block (replaced, never changed: read without a lock). */
    private val texts: Map<String, Entry>
        get() = store.value ?: load().let { loaded -> if (store.compareAndSet(null, loaded)) loaded else store.value!! }

    /** Keys of the blocks of the previous capture: unchanged since then, the student is done with them. */
    @Volatile
    private var previousKeys: Set<String> = emptySet()

    @Serializable
    data class Entry(val pageId: String = "", val text: String)

    /** [snapshot] with the handwriting transcribed so far in its pages. */
    fun fill(snapshot: NotebookSnapshot): NotebookSnapshot =
        snapshot.withPages(snapshot.pages.map { page -> fill(page, snapshot.inkBlocks.filter { it.pageIndex == page.index }) })

    /** [page] with the transcribed text of its [blocks], from the top. */
    fun fill(page: PageContent, blocks: List<InkBlock>): PageContent {
        if (blocks.isEmpty()) return page
        val known = blocks.sortedBy { it.bounds.top }.mapNotNull { texts[it.key]?.text?.takeIf { text -> text.isNotBlank() } }
        return page.copy(handwriting = known, inkTranscribed = blocks.all { it.key in texts })
    }

    /** Whether [block] is transcribed already. */
    fun isRead(block: InkBlock): Boolean = block.key in texts

    /**
     * Transcribes at most [max] blocks of [blocks] (in their order) not transcribed yet. With
     * [stableOnly], only the blocks that did not change since the previous call or that were
     * written before the lecture ([initialStrokeIds]): the student may still be writing the others.
     *
     * @param pages the pages of the blocks (the text of their slides helps reading the notes).
     * @return the tokens of the requests (none for a local model), `null` when nothing was sent.
     */
    suspend fun read(
        blocks: List<InkBlock>,
        pages: List<PageContent>,
        source: NotebookSource,
        task: TaskModel,
        max: Int,
        stableOnly: Boolean,
        initialStrokeIds: Set<String> = emptySet()
    ): TokenUsage? = mutex.withLock {
        val candidates = blocks.filter { block ->
            block.key !in texts && (!stableOnly || block.key in previousKeys || initialStrokeIds.containsAll(block.strokeIds))
        }.take(max)
        if (stableOnly) previousKeys = blocks.mapTo(mutableSetOf()) { it.key }
        if (candidates.isEmpty()) return@withLock null
        var usage: TokenUsage? = null
        val batch = if (task.provider == ModelProvider.LOCAL) LOCAL_BATCH else ONLINE_BATCH
        for (group in candidates.chunked(batch)) {
            val images = group.mapNotNull { block -> source.renderInk(block)?.let { block to it } }
            if (images.isEmpty()) continue
            val prompt = prompt(images, pages)
            val answer = when (task.provider) {
                ModelProvider.GEMINI -> backends.gemini.generate(prompt, task.gemini)
                ModelProvider.LOCAL -> backends.local.generate(prompt, task.local, OllamaBackend.LIVE_KEEP_LOADED_SECONDS) {}
            }
            val read = LectureAnalysis.parseHandwriting(answer.text)
            // A block the model did not answer stays to be read
            val newTexts = images.withIndex().mapNotNull { (i, image) ->
                read[i + 1]?.let { text -> image.first.key to Entry(image.first.pageId, text.trim()) }
            }
            store.value = texts + newTexts
            usage = (usage ?: TokenUsage()) + (answer.usage ?: TokenUsage(localRequests = 1))
        }
        save()
        usage
    }

    private fun prompt(images: List<Pair<InkBlock, ByteArray>>, pages: List<PageContent>): ModelPrompt {
        val parts = mutableListOf<PromptPart>()
        images.forEachIndexed { i, (block, jpeg) ->
            val slide = pages.firstOrNull { it.index == block.pageIndex }?.slide?.text?.take(SLIDE_HINT_CHARS)
            parts.add(
                PromptPart.Text(
                    "Image ${i + 1}: notebook page ${block.pageIndex + 1}" +
                        (if (!slide.isNullOrBlank()) ", written over a slide that reads: \"${slide.replace('\n', ' ')}\"" else "") + "."
                )
            )
            parts.add(PromptPart.Image(jpeg))
        }
        return ModelPrompt(
            system = SYSTEM,
            parts = parts,
            schema = schema,
            maxOutputTokens = 4096,
            thinkingLevel = "low"
        )
    }

    private fun load(): Map<String, Entry> = readFile(path)

    private suspend fun save() {
        fileLock.withLock {
            // With what the other devices read meanwhile
            val all = LinkedHashMap(texts)
            for ((key, entry) in readFile(path)) if (key !in all) all[key] = entry
            // The oldest entries go first (insertion order)
            while (all.size > MAX_ENTRIES) all.remove(all.keys.first())
            store.value = all
            writeAtomically(path, encode(all).encodeToByteArray())
        }
        onSaved()
    }

    companion object {
        /** Blocks per request: the local models read few images at a time. */
        const val LOCAL_BATCH = 3
        const val ONLINE_BATCH = 6

        /** Blocks transcribed after each update during the lecture, and at its end. */
        const val MAX_PER_UPDATE_LOCAL = 3
        const val MAX_PER_UPDATE_ONLINE = 6
        const val MAX_AT_THE_END = 20

        private const val SLIDE_HINT_CHARS = 300
        private const val MAX_ENTRIES = 5000

        private val json = Json { ignoreUnknownKeys = true }
        private val storeSerializer = MapSerializer(String.serializer(), Entry.serializer())

        /** Held while a file of the handwriting is read and written (here, or with what another device read). */
        val fileLock = Mutex()

        /** The handwriting read in the file [path]. */
        fun readFile(path: String): Map<String, Entry> = PlatformFiles.readBytes(path)?.let { decode(it) }.orEmpty()

        fun decode(bytes: ByteArray): Map<String, Entry>? = try {
            json.decodeFromString(storeSerializer, bytes.decodeToString())
        } catch (_: Exception) {
            null
        }

        fun encode(texts: Map<String, Entry>): String = json.encodeToString(storeSerializer, texts)

        /** The handwriting read here and on another device: all of it (of a block read on both, the same one on both). */
        fun merge(mine: Map<String, Entry>, theirs: Map<String, Entry>): Map<String, Entry> {
            val merged = LinkedHashMap(mine)
            for ((key, entry) in theirs) {
                val known = merged[key]
                if (known == null || (known != entry && entry.text > known.text)) merged[key] = entry
            }
            return merged
        }

        /** The same bytes for the same handwriting (whatever the order it was read in). */
        fun canonical(texts: Map<String, Entry>): String = encode(texts.entries.sortedBy { it.key }.associate { it.toPair() })

        private val SYSTEM = """
            You transcribe the handwritten notes of a university student, taken during a lecture. Every image shows one block of handwriting.
            Write exactly what is written in each image:
            - the text in Markdown, keeping its lines and bullet points ("-"); formulas in LaTeX between ${'$'}...${'$'};
            - arrows as →, drawings and diagrams as [figure: a few words on what it shows];
            - illegible words as [?].
            Do not correct, complete, translate or explain anything. An image with nothing readable: an empty text.
            Answer with one entry per image, with its number.
        """.trimIndent()

        private val schema: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("blocks") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("image") { put("type", "integer") }
                            putJsonObject("text") { put("type", "string") }
                        }
                        putJsonArray("required") {
                            add("image")
                            add("text")
                        }
                    }
                }
            }
            putJsonArray("required") { add("blocks") }
        }
    }
}
