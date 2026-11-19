package io.github.valerioisufi.drawview.canvas.controller

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * When the text being written is saved (see [TextEditorManager.saveWriting]): [idleMs] after its
 * last change, and at most [longestMs] after its first change not saved, so that a writing without
 * pauses is saved too. It runs [save] on [scope], the one of the editor.
 */
internal class WritingSaver(
    private val scope: CoroutineScope,
    private val idleMs: Long = IDLE_MS,
    private val longestMs: Long = LONGEST_MS,
    private val save: () -> Unit
) {
    /** The save after the pause in the writing. */
    private var idle: Job? = null

    /** The save of a writing that goes on without pauses. */
    private var longest: Job? = null

    /** The text written changed: saved after a pause, or after the longest wait. */
    fun changed() {
        idle?.cancel()
        idle = scope.launch {
            delay(idleMs)
            saveNow()
        }
        if (longest?.isActive != true) {
            longest = scope.launch {
                delay(longestMs)
                saveNow()
            }
        }
    }

    /** Nothing left to save (saved, or the writing ended). */
    fun stop() {
        idle?.cancel()
        longest?.cancel()
        idle = null
        longest = null
    }

    private fun saveNow() {
        stop()
        save()
    }

    companion object {
        /** The pause in the writing after which it is saved. */
        const val IDLE_MS = 2_000L

        /** The longest a change waits to be saved, written without pauses. */
        const val LONGEST_MS = 10_000L
    }
}
