package io.github.valerioisufi.drawview.lecture.audio

/** What the system did to the microphone while capturing. */
public enum class CaptureEvent {
    /** Taken by the system (a call, Siri, another application): no audio until [RESUMED]. */
    INTERRUPTED,

    /** Given back: the capture goes on by itself. */
    RESUMED,

    /** Another microphone (headphones plugged or unplugged): the capture goes on with it. */
    DEVICE_CHANGED
}

/**
 * Microphone capture delivering 16-bit mono PCM at [LECTURE_SAMPLE_RATE] Hz.
 */
public interface AudioCapture {
    /**
     * Starts the capture: [onSamples] is called on a capture thread with blocks of about 100 ms,
     * [onEvent] when the system interrupts the microphone or changes it (the capture goes on by
     * itself afterwards).
     *
     * @throws AudioCaptureException when no microphone can be opened.
     */
    public fun start(onSamples: (ShortArray) -> Unit, onEvent: (CaptureEvent) -> Unit = {})

    public fun stop()
}

internal class AudioCaptureException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A stream of audio towards the speakers (16-bit mono PCM at [LECTURE_SAMPLE_RATE] Hz). */
public interface AudioOutput {
    /**
     * Opens the output: `false` when it cannot be opened. [onStopped] is called when the system
     * stops the playback (headphones unplugged, a call): the output is to be closed.
     */
    public fun open(onStopped: () -> Unit = {}): Boolean

    /** Writes the first [count] samples of [samples], waiting while the buffer of the output is full. */
    public fun write(samples: ShortArray, count: Int = samples.size)

    /** Samples written and not played yet. */
    public val pending: Int

    /** Stops right away (what is not played yet is discarded) and closes the output. */
    public fun close()
}

/** The microphone of the platform, or `null` when the recording is not supported (yet). */
internal expect fun createAudioCapture(): AudioCapture?

/** An audio output of the platform, or `null` when the playback is not supported (yet). */
internal expect fun createAudioOutput(): AudioOutput?
