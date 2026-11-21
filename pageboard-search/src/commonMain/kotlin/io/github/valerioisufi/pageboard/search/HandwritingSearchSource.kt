package io.github.valerioisufi.pageboard.search

import io.github.valerioisufi.pageboard.handwriting.HandwritingReading
import io.github.valerioisufi.pageboard.handwriting.InkSample
import io.github.valerioisufi.pageboard.model.geometry.Rect
import io.github.valerioisufi.pageboard.model.ink.blockKey
import io.github.valerioisufi.pageboard.model.ink.groupStrokes
import io.github.valerioisufi.pageboard.model.ink.isHandwriting
import io.github.valerioisufi.pageboard.model.ink.unionOf
import io.github.valerioisufi.pageboard.platform.Sha256
import io.github.valerioisufi.pageboard.store.DocumentStore
import io.github.valerioisufi.pageboard.store.DocumentSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The handwriting of every document, read by the readers of the device ([reading]): the strokes
 * of each page grouped in blocks (`groupStrokes`), each block read once in the language of the
 * notes ([language]) and kept in the index by its key (`StoredHandwriting`), read again only when
 * its strokes or its reader change. A chunk a block holds its most likely reading; the other
 * candidates are found by the words only (`SearchChunk.extraWords`).
 *
 * The strokes are read without modeling them ([DocumentStore.loadInk]), and only for a document
 * whose pages changed ([DocumentStore.pageFingerprint]). Without a reader of the language, or
 * when [isEnabled] says no, the source has nothing.
 *
 * @param changes emits when the reading is turned on or off, or the language or the readers change.
 */
public class HandwritingSearchSource(
    private val store: DocumentStore,
    private val index: SearchIndex,
    private val reading: HandwritingReading,
    private val language: () -> String,
    private val isEnabled: () -> Boolean = { true },
    override val changes: Flow<Unit> = emptyFlow()
) : SearchSource {
    override val id: String = ID

    override suspend fun version(document: DocumentSummary): String? {
        if (!isEnabled()) return null
        val language = language()
        val reader = reading.version(language) ?: return null
        val fingerprints = buildString {
            for (i in 0 until MAX_PAGES) {
                val fingerprint = store.pageFingerprint(document.id, i) ?: break
                append(fingerprint).append('\n')
            }
        }
        if (fingerprints.isEmpty()) return null
        return Sha256.of("$reader|$language|$fingerprints".encodeToByteArray())
    }

    override suspend fun chunks(document: DocumentSummary): List<SearchChunk> {
        if (!isEnabled()) return emptyList()
        val language = language()
        val reader = reading.version(language) ?: return emptyList()
        val ink = store.loadInk(document.id) ?: return emptyList()
        val known = index.handwriting(document.id)
        val read = mutableListOf<StoredHandwriting>()
        val keys = mutableSetOf<String>()
        val chunks = mutableListOf<SearchChunk>()
        ink.pages.forEachIndexed { pageIndex, page ->
            val writing = page.strokes.filter { it.tool.isHandwriting && it.points.isNotEmpty() }
            if (writing.isEmpty()) return@forEachIndexed
            val samples = writing.map { InkSample.ofData(listOf(it)) }
            val bounds = samples.map { it.bounds ?: Rect.Zero }
            for (group in groupStrokes(bounds)) {
                val key = blockKey(group.map { writing[it].id })
                keys.add(key)
                val stored = known[key]?.takeIf { it.reader == reader && it.language == language }
                    ?: readBlock(key, group.map { samples[it] }, reader, language)?.also { read.add(it) }
                    ?: continue
                if (stored.text.isBlank()) continue
                chunks.add(
                    SearchChunk(
                        sourceId = ID,
                        kind = SearchChunkKind.HANDWRITING,
                        anchor = SearchAnchor.Page(document.id, page.id, unionOf(group.map { bounds[it] }), pageIndex + 1),
                        title = document.name,
                        text = stored.text,
                        extraWords = stored.searchText.takeIf { it != stored.text }.orEmpty()
                    )
                )
            }
        }
        index.putHandwriting(document.id, read, keys)
        return chunks
    }

    /**
     * The handwriting of the page [pageId] of [documentId], its blocks from the top a line under the
     * other (the readings kept in the index, the others read now), `null` when the page has none or
     * nothing is read: what the external models read of a page.
     */
    public suspend fun pageText(documentId: String, pageId: String): String? {
        if (!isEnabled()) return null
        val language = language()
        val reader = reading.version(language) ?: return null
        val page = store.loadInk(documentId)?.pages?.firstOrNull { it.id == pageId } ?: return null
        val known = index.handwriting(documentId)
        val writing = page.strokes.filter { it.tool.isHandwriting && it.points.isNotEmpty() }
        if (writing.isEmpty()) return null
        val samples = writing.map { InkSample.ofData(listOf(it)) }
        val bounds = samples.map { it.bounds ?: Rect.Zero }
        val texts = groupStrokes(bounds).map { group ->
            val key = blockKey(group.map { writing[it].id })
            val stored = known[key]?.takeIf { it.reader == reader && it.language == language }
                ?: readBlock(key, group.map { samples[it] }, reader, language)
            stored?.text.orEmpty()
        }.filter { it.isNotBlank() }
        return texts.joinToString("\n").ifEmpty { null }
    }

    /**
     * The reading of a block, `null` when the reader stopped reading the language while it read it
     * (PencilKit, when the application leaves the foreground): such a block is not kept, and is read
     * when the reader comes back.
     */
    private suspend fun readBlock(key: String, samples: List<InkSample>, reader: String, language: String): StoredHandwriting? {
        val ink = InkSample(samples.flatMap { it.strokes })
        val read = reading.readText(ink, language)
        if (read == null && reading.version(language) != reader) return null
        // A block read as nothing (a drawing) is kept so, not read again
        return StoredHandwriting(key, reader, language, read?.text.orEmpty(), read?.searchText.orEmpty())
    }

    public companion object {
        /** The identifier of the source in the index. */
        public const val ID: String = "handwriting"

        /** The pages of a document looked at for its version, at most. */
        private const val MAX_PAGES = 10_000
    }
}
