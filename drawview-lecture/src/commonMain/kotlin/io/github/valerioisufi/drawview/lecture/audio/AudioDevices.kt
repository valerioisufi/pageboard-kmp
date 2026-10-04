package io.github.valerioisufi.drawview.lecture.audio

/**
 * Microphone capture delivering 16-bit mono PCM at [LECTURE_SAMPLE_RATE] Hz.
 */
interface AudioCapture {
    /**
     * Starts the capture: [onSamples] is called on a capture thread with blocks of about 100 ms.
     *
     * @throws AudioCaptureException when no microphone can be opened.
     */
    fun start(onSamples: (ShortArray) -> Unit)

    fun stop()
}

class AudioCaptureException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Plays short excerpts of a recording (16-bit mono PCM at [LECTURE_SAMPLE_RATE] Hz). */
interface AudioPlayer {
    /** Plays [samples], stopping the excerpt being played. */
    fun play(samples: ShortArray)

    fun stop()
}

/** The microphone of the platform, or `null` when the recording is not supported (yet). */
expect fun createAudioCapture(): AudioCapture?

/** The audio output of the platform, or `null` when the playback is not supported (yet). */
expect fun createAudioPlayer(): AudioPlayer?
