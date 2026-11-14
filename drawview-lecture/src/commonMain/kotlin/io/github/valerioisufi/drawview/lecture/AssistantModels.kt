package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.lecture.gemini.GeminiClient
import io.github.valerioisufi.drawview.lecture.handwriting.HandwritingReader
import io.github.valerioisufi.drawview.lecture.local.OllamaClient
import io.github.valerioisufi.drawview.lecture.model.GeminiBackend
import io.github.valerioisufi.drawview.lecture.model.ModelBackends
import io.github.valerioisufi.drawview.lecture.model.ModelPrompt
import io.github.valerioisufi.drawview.lecture.model.ModelUse
import io.github.valerioisufi.drawview.lecture.model.OllamaBackend
import io.github.valerioisufi.drawview.lecture.model.PromptPart
import io.github.valerioisufi.drawview.lecture.model.TaskBackend
import io.github.valerioisufi.drawview.lecture.transcription.Transcriber
import io.github.valerioisufi.drawview.lecture.transcription.Transcribers
import io.github.valerioisufi.drawview.platform.SyncLock
import io.github.valerioisufi.drawview.platform.syncLock
import kotlin.coroutines.CoroutineContext

/**
 * The models of a [LectureAssistant] (Information Expert, Creator): the backends that answer its
 * requests, the speech recognition, the readers of the handwriting of every notebook, and the
 * requests made outside a lecture (the course context, the text notes, the answers of the search).
 */
internal class AssistantModels(
    private val settingsStore: AssistantSettingsStore,
    client: GeminiClient,
    localClient: OllamaClient,
    whisper: (AssistantSettings) -> Transcriber,
    private val library: LectureLibrary,
    stateContext: CoroutineContext
) {
    val backends = ModelBackends(
        gemini = GeminiBackend(client) { settingsStore.apiKey },
        local = OllamaBackend(localClient) { settingsStore.settings.localUrl }
    )

    /** The speech recognition of this computer. */
    val transcribers = Transcribers(whisper)

    /** The models on this computer (Ollama, whisper.cpp), for the settings. */
    val localModels: LocalModels = LocalModels(settingsStore, localClient, stateContext = stateContext)

    private val handwritingReaders = mutableMapOf<String, HandwritingReader>()
    private val handwritingLock = SyncLock()

    /** The reader of the handwriting of [documentId], shared by its recordings and its reviews. */
    fun handwritingOf(documentId: String): HandwritingReader = syncLock(handwritingLock) {
        handwritingReaders.getOrPut(documentId) {
            HandwritingReader(library.documentDirectory(documentId) + "/handwriting.json") { library.changed(documentId, null) }
        }
    }

    /** The course context written by the model of the live suggestions, from the whole [notebook] of [documentId]. */
    suspend fun composeContext(documentId: String, notebook: NotebookSource): String? {
        val task = backends.forTask(settingsStore.settings, LectureTask.LIVE_NOTES)
        if (task.unavailable() != null) return null
        val pages = notebook.allPages()
        if (pages.isEmpty()) return null
        // With the handwriting already transcribed
        val prompt = LecturePrompts.courseContextPrompt(settingsStore.settings, notebook.documentTitle, filledPages(documentId, notebook, pages))
        val answer = task.generate(prompt, ModelUse.LIVE)
        return LectureAnalysis.parseCourseContext(answer.text)
    }

    /** What turning [text] into notes for the [notebook] of [documentId] would cost (see [textNotes]). */
    suspend fun estimateTextNotes(documentId: String, notebook: NotebookSource, text: String): TextNotesEstimate {
        val prompt = textNotesPrompt(documentId, notebook, text)
        val task = backends.forTask(settingsStore.settings, LectureTask.FINAL_REVIEW)
        val chars = prompt.system.length + prompt.parts.sumOf { (it as? PromptPart.Text)?.text?.length ?: 0 }
        return TextNotesEstimate(
            provider = task.provider,
            model = task.model,
            chars = chars,
            tokens = chars / TextNotes.CHARS_PER_TOKEN,
            tooLong = text.length > TextNotes.MAX_TEXT_CHARS,
            missingApiKey = task.unavailable() == LectureProblem.MissingApiKey
        )
    }

    /**
     * [text] turned into notes for the [notebook] of [documentId] by the model of the task of the
     * review (see [LectureAssistant.textNotes]).
     */
    suspend fun textNotes(documentId: String, notebook: NotebookSource, text: String): TextNotes {
        check(text.length <= TextNotes.MAX_TEXT_CHARS) { "The text is too long" }
        val task = backends.forTask(settingsStore.settings, LectureTask.FINAL_REVIEW)
        check(task.unavailable() == null) { "Gemini has no key" }
        val answer = task.generate(textNotesPrompt(documentId, notebook, text))
        return TextNotes.of(LectureAnalysis.parseTextNotes(answer.text), notebook.pageIds(), text)
    }

    /** The request turning [text] into notes for [documentId], with the notebook as the review sees it. */
    private suspend fun textNotesPrompt(documentId: String, notebook: NotebookSource, text: String): ModelPrompt =
        LecturePrompts.textNotesPrompt(
            settingsStore.settings,
            notebook.documentTitle,
            filledPages(documentId, notebook, notebook.allPages()),
            text.take(TextNotes.MAX_TEXT_CHARS)
        )

    /** [pages] of [notebook] with the handwriting of [documentId] read so far. */
    private suspend fun filledPages(documentId: String, notebook: NotebookSource, pages: List<PageContent>): List<PageContent> {
        val reader = handwritingOf(documentId)
        val blocks = notebook.inkBlocks(pages.filter { it.hasInk }.map { it.index })
        return pages.map { page -> reader.fill(page, blocks.filter { it.pageIndex == page.index }) }
    }

    /** The backend of the answers online (the search): Gemini, with the model of the live notes. */
    private val onlineAnswers: TaskBackend
        get() = TaskBackend(backends.gemini, settingsStore.settings.task(LectureTask.LIVE_NOTES).gemini)

    /** Whether a question can be answered online now (see [LectureAssistant.canAnswerOnline]). */
    val canAnswerOnline: Boolean get() = onlineAnswers.unavailable() == null

    /** [prompt] answered online in plain text, with the name of the model (see [LectureAssistant.answerOnline]). */
    suspend fun answerOnline(systemInstruction: String, prompt: String): Pair<String, String> {
        val task = onlineAnswers
        check(task.unavailable() == null) { "Gemini API key not set" }
        val request = ModelPrompt(
            system = systemInstruction,
            parts = listOf(PromptPart.Text(prompt)),
            schema = null,
            maxOutputTokens = ONLINE_ANSWER_TOKENS,
            thinkingLevel = settingsStore.settings.thinkingLevel
        )
        return task.generate(request).text to task.model
    }
}
