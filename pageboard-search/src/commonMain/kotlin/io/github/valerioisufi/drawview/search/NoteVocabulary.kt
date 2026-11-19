package io.github.valerioisufi.drawview.search

import io.github.valerioisufi.drawview.platform.SyncLock
import io.github.valerioisufi.drawview.platform.syncLock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The words of the notes as the search indexed them (`docs/plans/writing-suggestions.md`): of the whole
 * library, and of what each document has besides its typed texts (its slides, its handwriting,
 * its lectures). It is the vocabulary from which the end of a word being written is suggested.
 *
 * The counts are built on [dispatcher] in [scope], never by the caller: asked for words not built
 * yet, it answers what it has (`null` the first time) and builds them, so that a suggestion is
 * never late. The words of a document are built again when the index changed; those of the
 * library at most once every [libraryRefresh], since the library changes little while one writes.
 * Safe to use from several threads.
 */
public class NoteVocabulary(
    private val index: SearchIndex,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val libraryRefresh: Duration = LIBRARY_REFRESH,
    private val timeSource: TimeSource = TimeSource.Monotonic
) {
    /** Counts built from the index at [revision], at [builtAt]. */
    private class Built(val revision: Long, val builtAt: TimeMark, val counts: WordCounts)

    /** Words being built or built, by the document they are of (`null` for the library). */
    private class Entry {
        var built: Built? = null
        var job: Job? = null
    }

    private val lock = SyncLock()
    private val library = Entry()

    /** The documents asked for last, the last at the end; [MAX_DOCUMENTS] at most. */
    private val documents = LinkedHashMap<String, Entry>()

    /** The words of every document of the library, their names left out, or `null` before they are first built. */
    public fun library(): WordCounts? = current(library, documentId = null)

    /** The words of [documentId] besides its typed texts (slides, handwriting, lectures), or `null` before they are first built. */
    public fun document(documentId: String): WordCounts? {
        val entry = syncLock(lock) {
            documents.remove(documentId)?.also { documents[documentId] = it } ?: Entry().also {
                documents[documentId] = it
                if (documents.size > MAX_DOCUMENTS) documents.remove(documents.keys.first())
            }
        }
        return current(entry, documentId)
    }

    private fun current(entry: Entry, documentId: String?): WordCounts? {
        val revision = index.revision.value
        return syncLock(lock) {
            val built = entry.built
            val stale = built == null || built.revision != revision &&
                (documentId != null || built.builtAt.elapsedNow() >= libraryRefresh)
            if (stale && entry.job?.isActive != true) {
                entry.job = scope.launch(dispatcher) { build(entry, documentId, revision) }
            }
            built?.counts
        }
    }

    private fun build(entry: Entry, documentId: String?, revision: Long) {
        val kinds = if (documentId == null) LIBRARY_KINDS else DOCUMENT_KINDS
        // A closed index (the application exiting) keeps the words built before
        val texts = runCatching { index.texts(documentId, kinds) }.getOrNull() ?: return
        val counts = WordCounts()
        for (text in texts) counts.add(text)
        syncLock(lock) { entry.built = Built(revision, timeSource.markNow(), counts) }
    }

    public companion object {
        /** The longest time the words of the library are not built again while the index changes. */
        public val LIBRARY_REFRESH: Duration = 2.minutes

        /** The documents whose words are kept, the ones asked for last. */
        public const val MAX_DOCUMENTS: Int = 8

        /** What the library counts: all but the names of the documents and their folders. */
        private val LIBRARY_KINDS = SearchChunkKind.entries.toSet() - SearchChunkKind.TITLE

        /** What a document counts: what it has besides its typed texts, which the editor reads as they are. */
        private val DOCUMENT_KINDS = LIBRARY_KINDS - SearchChunkKind.TEXT
    }
}
