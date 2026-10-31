package io.github.valerioisufi.drawview.lecture.ui

import io.github.valerioisufi.drawview.lecture.LectureAssistant
import io.github.valerioisufi.drawview.lecture.LectureLibrary
import io.github.valerioisufi.drawview.lecture.SuggestionStatus
import io.github.valerioisufi.drawview.lecture.TranscriptSegment
import io.github.valerioisufi.drawview.platform.PlatformFiles
import io.github.valerioisufi.drawview.platform.Sha256
import io.github.valerioisufi.drawview.search.OnlineAnswerBackend
import io.github.valerioisufi.drawview.search.SearchAnchor
import io.github.valerioisufi.drawview.search.SearchChunk
import io.github.valerioisufi.drawview.search.SearchChunkKind
import io.github.valerioisufi.drawview.search.SearchSource
import io.github.valerioisufi.drawview.search.cleanMarkdownForSearch
import io.github.valerioisufi.drawview.search.splitTextChunks
import io.github.valerioisufi.drawview.store.DocumentStore
import io.github.valerioisufi.drawview.store.DocumentSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Indexes the lectures of a document (the transcript in windows of about a minute, the final
 * review by section, the suggestions not dismissed) and the handwriting read by the assistant
 * (`handwriting.json` of the notebook) (Information Expert).
 */
public class LectureSearchSource(
    private val library: LectureLibrary,
    private val store: DocumentStore? = null,
    override val changes: Flow<Unit> = emptyFlow()
) : SearchSource {
    override val id: String = ID

    override suspend fun version(document: DocumentSummary): String? {
        val summaries = library.list(document.id).sortedBy { it.directory }
        val handwritingBytes = PlatformFiles.readBytes(handwritingPath(document.id))
        if (summaries.isEmpty() && handwritingBytes == null) return null

        val lecturesVersion = summaries.joinToString(";") { summary ->
            val lectureId = summary.directory.substringAfterLast('/')
            val record = library.load(summary.directory)
            val stamps = record?.stamps?.entries
                ?.sortedBy { it.key.name }
                ?.joinToString(",") { "${it.key.name}:${it.value.at}:${it.value.by}" }
                .orEmpty()
            val suggestions = record?.suggestions
                ?.joinToString(",") { "${it.id}:${it.status.name}" }
                .orEmpty()
            val transcriptCount = record?.transcript?.size ?: 0
            "$lectureId:${summary.durationMs}:${summary.hasReview}:$transcriptCount:$stamps:$suggestions"
        }
        val handwritingHash = handwritingBytes?.let { Sha256.of(it) }.orEmpty()
        return "${document.name}|$lecturesVersion|$handwritingHash"
    }

    override suspend fun chunks(document: DocumentSummary): List<SearchChunk> {
        val result = mutableListOf<SearchChunk>()
        val summaries = library.list(document.id).sortedBy { it.startedAt }
        for (summary in summaries) {
            val lectureId = summary.directory.substringAfterLast('/')
            val record = library.load(summary.directory) ?: continue

            for ((windowStartMs, windowText) in windowTranscript(record.transcript)) {
                result.add(
                    SearchChunk(
                        sourceId = ID,
                        kind = SearchChunkKind.LECTURE_TRANSCRIPT,
                        anchor = SearchAnchor.Lecture(
                            documentId = document.id,
                            lectureId = lectureId,
                            startMs = windowStartMs
                        ),
                        title = document.name,
                        text = windowText
                    )
                )
            }

            val review = record.finalReview
            if (!review.isNullOrBlank()) {
                val cleaned = cleanMarkdownForSearch(review)
                if (cleaned.isNotEmpty()) {
                    for (piece in splitTextChunks(cleaned)) {
                        result.add(
                            SearchChunk(
                                sourceId = ID,
                                kind = SearchChunkKind.LECTURE_NOTES,
                                anchor = SearchAnchor.Lecture(
                                    documentId = document.id,
                                    lectureId = lectureId,
                                    startMs = 0L
                                ),
                                title = document.name,
                                text = piece
                            )
                        )
                    }
                }
            }

            for (suggestion in record.suggestions) {
                if (suggestion.status == SuggestionStatus.DISMISSED) continue
                val raw = listOf(suggestion.title.trim(), suggestion.content.trim())
                    .filter { it.isNotEmpty() }
                    .joinToString("\n")
                val cleaned = cleanMarkdownForSearch(raw)
                if (cleaned.isEmpty()) continue
                for (piece in splitTextChunks(cleaned)) {
                    result.add(
                        SearchChunk(
                            sourceId = ID,
                            kind = SearchChunkKind.LECTURE_NOTES,
                            anchor = SearchAnchor.Lecture(
                                documentId = document.id,
                                lectureId = lectureId,
                                startMs = suggestion.atMs.coerceAtLeast(0L)
                            ),
                            title = document.name,
                            text = piece
                        )
                    )
                }
            }
        }

        val handwritingBytes = PlatformFiles.readBytes(handwritingPath(document.id))
        if (handwritingBytes != null) {
            val entries = parseHandwriting(handwritingBytes)
            if (entries.isNotEmpty()) {
                val outline = store?.loadOutline(document.id)
                val pageNumbersById = outline?.pages
                    ?.mapIndexed { index, page -> page.id to (index + 1) }
                    ?.toMap()
                    .orEmpty()
                val byPage = LinkedHashMap<String, MutableList<String>>()
                for ((pageId, text) in entries) {
                    val cleaned = cleanMarkdownForSearch(text)
                    if (cleaned.isNotEmpty()) {
                        byPage.getOrPut(pageId) { ArrayList() }.add(cleaned)
                    }
                }
                for ((pageId, texts) in byPage) {
                    val pageNumber = pageNumbersById[pageId] ?: 1
                    val joined = texts.joinToString("\n\n")
                    for (piece in splitTextChunks(joined)) {
                        result.add(
                            SearchChunk(
                                sourceId = ID,
                                kind = SearchChunkKind.HANDWRITING,
                                anchor = SearchAnchor.Page(
                                    documentId = document.id,
                                    pageId = pageId,
                                    bounds = null,
                                    pageNumber = pageNumber
                                ),
                                title = document.name,
                                text = piece
                            )
                        )
                    }
                }
            }
        }

        return result
    }

    private fun windowTranscript(segments: List<TranscriptSegment>): List<Pair<Long, String>> {
        val nonBlank = segments.filter { it.text.isNotBlank() }
        if (nonBlank.isEmpty()) return emptyList()
        val windows = mutableListOf<Pair<Long, String>>()
        var windowStartMs = nonBlank.first().startMs.coerceAtLeast(0L)
        val buffer = StringBuilder()

        for (segment in nonBlank) {
            val text = segment.text.trim()
            if (buffer.isNotEmpty() && segment.startMs - windowStartMs >= TRANSCRIPT_WINDOW_MS) {
                windows.add(windowStartMs to buffer.toString().trim())
                buffer.clear()
                windowStartMs = segment.startMs.coerceAtLeast(0L)
            }
            if (buffer.isNotEmpty()) buffer.append(' ')
            buffer.append(text)
        }
        if (buffer.isNotEmpty()) {
            windows.add(windowStartMs to buffer.toString().trim())
        }
        return windows
    }

    private fun handwritingPath(documentId: String): String =
        "${library.documentDirectory(documentId)}/handwriting.json"

    private fun parseHandwriting(bytes: ByteArray): List<Pair<String, String>> = try {
        val root = json.parseToJsonElement(bytes.decodeToString()).jsonObject
        root.entries
            .sortedBy { it.key }
            .mapNotNull { (_, element) ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val pageId = (obj["pageId"] as? JsonPrimitive)?.content.orEmpty()
                val text = (obj["text"] as? JsonPrimitive)?.content?.trim().orEmpty()
                if (text.isEmpty()) null else pageId to text
            }
    } catch (_: Exception) {
        emptyList()
    }

    public companion object {
        public const val ID: String = "lecture"
        internal const val TRANSCRIPT_WINDOW_MS: Long = 60_000L
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * [SearchSource] indexing the lectures (transcript, review, suggestions) and the handwriting read
 * by this [LectureAssistant] for the documents of [store].
 */
public fun LectureAssistant.searchSource(store: DocumentStore? = null): SearchSource =
    LectureSearchSource(
        library = library,
        store = store,
        changes = attachments.changes.map {}
    )

/**
 * [OnlineAnswerBackend] for "Ask your notes" (`drawview-search`) backed by Google Gemini when the
 * student has configured an API key in this [LectureAssistant].
 */
public fun LectureAssistant.onlineAnswerBackend(): OnlineAnswerBackend = OnlineAnswerBackend(
    isAvailable = { canAnswerWithGemini },
    generate = { systemInstruction, prompt -> answerWithGemini(systemInstruction, prompt) }
)
