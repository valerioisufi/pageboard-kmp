package io.github.valerioisufi.drawview.lecture.platform

/** A program started by the application (e.g. the transcription server of whisper.cpp). */
interface LocalProcess {
    val isAlive: Boolean

    /** The last lines written by the program (to explain why it stopped). */
    fun recentOutput(): String

    /** Stops the program. */
    fun stop()
}

/**
 * Starts [command] (the program, then its arguments), or returns `null` where the application
 * cannot start programs (mobile platforms). The program is stopped when the application exits.
 *
 * @throws Exception when the program cannot be started.
 */
expect fun startLocalProcess(command: List<String>): LocalProcess?
