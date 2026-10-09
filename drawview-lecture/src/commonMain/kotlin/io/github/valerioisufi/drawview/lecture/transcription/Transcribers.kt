package io.github.valerioisufi.drawview.lecture.transcription

import io.github.valerioisufi.drawview.lecture.AssistantSettings
import io.github.valerioisufi.drawview.lecture.LectureTask
import io.github.valerioisufi.drawview.lecture.ModelProvider
import io.github.valerioisufi.drawview.lecture.TranscriptionMode
import io.github.valerioisufi.drawview.lecture.model.GeminiBackend

/**
 * Creates the transcribers of the settings ([AssistantSettings.transcription]): for the updates of
 * a recording, and for a whole recording (an import, a lecture transcribed again).
 *
 * @param whisper the speech recognition of this computer.
 */
class Transcribers(
    private val gemini: GeminiBackend,
    private val whisper: (AssistantSettings) -> Transcriber = { WhisperTranscriber.of(it) }
) {
    /**
     * The transcriber of the updates of a recording, `null` when the model of the suggestions hears
     * the audio itself ([TranscriptionMode.WITH_NOTES]).
     */
    fun forUpdates(settings: AssistantSettings): Transcriber? {
        val model = settings.task(LectureTask.TRANSCRIPTION).gemini
        return when (settings.transcription) {
            TranscriptionMode.LOCAL -> whisper(settings)
            TranscriptionMode.STREAMING -> GeminiSpeechTranscriber(gemini, AssistantSettings.recordedSpeechModel(model))
            TranscriptionMode.SPEECH_CHUNKS -> GeminiSpeechTranscriber(gemini, model)
            TranscriptionMode.PROMPT_CHUNKS -> GeminiPromptTranscriber(gemini, model, { settings })
            TranscriptionMode.WITH_NOTES -> null
        }
    }

    /**
     * The transcriber of a whole recording by [provider] (by default the one of the settings): a
     * chunk at a time, the live speech models replaced by their model for recorded audio.
     */
    fun forRecording(settings: AssistantSettings, provider: ModelProvider = settings.task(LectureTask.TRANSCRIPTION).provider): Transcriber {
        if (provider == ModelProvider.LOCAL) return whisper(settings)
        val model = recordingModel(settings, provider)
        return if (AssistantSettings.isSpeechModel(model)) GeminiSpeechTranscriber(gemini, model) else GeminiPromptTranscriber(gemini, model, { settings })
    }

    /** The online model transcribing a whole recording ([forRecording]); empty on this computer. */
    fun recordingModel(settings: AssistantSettings, provider: ModelProvider): String {
        if (provider == ModelProvider.LOCAL) return ""
        val model = settings.task(LectureTask.TRANSCRIPTION).gemini
        return if (AssistantSettings.isSpeechModel(model)) AssistantSettings.recordedSpeechModel(model) else model
    }
}
