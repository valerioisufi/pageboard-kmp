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

/** A stream of audio towards the speakers (16-bit mono PCM at [LECTURE_SAMPLE_RATE] Hz). */
interface AudioOutput {
    /** Opens the output: `false` when it cannot be opened. */
    fun open(): Boolean

    /** Writes the first [count] samples of [samples], waiting while the buffer of the output is full. */
    fun write(samples: ShortArray, count: Int = samples.size)

    /** Samples written and not played yet. */
    val pending: Int

    /** Stops right away (what is not played yet is discarded) and closes the output. */
    fun close()
}

/** The microphone of the platform, or `null` when the recording is not supported (yet). */
expect fun createAudioCapture(): AudioCapture?

/** An audio output of the platform, or `null` when the playback is not supported (yet). */
expect fun createAudioOutput(): AudioOutput?
