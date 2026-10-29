package io.github.valerioisufi.drawview.lecture.transcription

import io.github.valerioisufi.drawview.lecture.AssistantSettings

/**
 * Creates the transcribers of the lectures: whisper.cpp on this computer, for the updates of a
 * recording and for a whole recording (an import, a lecture transcribed again). The audio never
 * leaves the computer.
 *
 * @param whisper the speech recognition of this computer.
 */
internal class Transcribers(private val whisper: (AssistantSettings) -> Transcriber = { WhisperTranscriber.of(it) }) {
    /** The transcriber of the updates of a recording. */
    fun forUpdates(settings: AssistantSettings): Transcriber = whisper(settings)

    /** The transcriber of a whole recording, a chunk at a time. */
    fun forRecording(settings: AssistantSettings): Transcriber = whisper(settings)
}
