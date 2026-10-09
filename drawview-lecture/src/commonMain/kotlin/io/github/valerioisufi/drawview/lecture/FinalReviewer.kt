package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.lecture.handwriting.HandwritingReader
import io.github.valerioisufi.drawview.lecture.model.ModelBackends
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Writes the final review of a [lecture]: the handwriting of the pages seen not transcribed yet
 * (when its notebook is open), then the review of the whole lecture ([Lecture.runFinalReview]).
 *
 * Called when the lecture ends ([AssistantSettings.reviewAtTheEnd]) or when the student asks for
 * it ([start]).
 *
 * @param handwriting the reader of the notebook, `null` when the notebook cannot be read.
 */
internal class FinalReviewer(
    private val lecture: Lecture,
    private val settingsStore: AssistantSettingsStore,
    private val handwriting: HandwritingReader?
) {
    /**
     * Writes the review in the background, by [provider], with [notebook] if it is open.
     *
     * @return `false` when it cannot be written now (the lecture is not over, or a review is running).
     */
    fun start(provider: ModelProvider, notebook: NotebookSource?): Boolean {
        if (!lecture.claimReview(provider)) return false
        lecture.scope.launch {
            write(provider, notebook)
            lecture.persist()
        }
        return true
    }

    /** Writes the review by [provider], waiting for it. */
    suspend fun write(provider: ModelProvider, notebook: NotebookSource?) {
        lecture.runFinalReview(provider) {
            if (notebook != null) readRemainingHandwriting(notebook)
            // The models of the lecture make room for the one of the review
            lecture.backends.unloadLiveModels(settingsStore.settings)
        }
    }

    /** The handwriting of the pages seen not transcribed yet, with the model of the live suggestions. */
    private suspend fun readRemainingHandwriting(source: NotebookSource) {
        val reader = handwriting ?: return
        val task = settingsStore.settings.task(LectureTask.LIVE_NOTES)
        if (task.provider == ModelProvider.GEMINI && !settingsStore.hasApiKey) return
        try {
            val (pages, lastIds) = lecture.setStateResult { seenPageContents() to lastPageIds }
            val pageIds = source.pageIds().ifEmpty { lastIds }
            // The pages where they are now (they may have been moved)
            val current = pages.map { page -> page.copy(index = pageIds.indexOf(page.pageId).takeIf { it >= 0 } ?: page.index) }
            val blocks = source.inkBlocks(current.filter { it.hasInk }.map { it.index }.reversed())
            if (blocks.none { !reader.isRead(it) }) return
            val tokens = reader.read(blocks, current, source, task, HandwritingReader.MAX_AT_THE_END, stableOnly = false)
            val filled = current.map { page -> reader.fill(page, blocks.filter { it.pageIndex == page.index }) }
            // Saved with the index they had when seen
            val seen = pages.zip(filled).map { (before, page) -> page.copy(index = before.index) }
            lecture.setState {
                tokens?.let { usage += it }
                updateSeenPages(seen)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            println("DrawView: cannot read the handwriting: ${e.message}")
        }
    }
}

/** Frees the local models of the requests made during the lecture ([AssistantSettings.liveLocalModels]). */
internal suspend fun ModelBackends.unloadLiveModels(settings: AssistantSettings) {
    settings.liveLocalModels.forEach { local.unload(it) }
}
