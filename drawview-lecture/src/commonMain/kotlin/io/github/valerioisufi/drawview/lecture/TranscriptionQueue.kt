package io.github.valerioisufi.drawview.lecture

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The transcriptions of whole recordings (an import, a lecture received without a transcript, a
 * lecture transcribed again), one at a time in the order they were asked (Pure Fabrication): a
 * transcription takes the speech model and minutes of the computer, so the others wait their turn
 * instead of being refused (see `docs/transcription-queue.md`).
 *
 * The queue is in memory. Its state ([lectures], [running]) is read and written on [stateContext];
 * the work runs in [scope].
 */
internal class TranscriptionQueue(private val scope: CoroutineScope, private val stateContext: CoroutineContext) {
    private class Entry(val lecture: Lecture, val job: Job)

    private val entries = Channel<Entry>(Channel.UNLIMITED)

    /** The lectures in the queue, in their order: the one being transcribed first, then those waiting. */
    var lectures: List<Lecture> by mutableStateOf(emptyList())
        private set

    /** The lecture whose transcription runs now, if any. */
    var running: Lecture? by mutableStateOf(null)
        private set

    init {
        scope.launch {
            for (entry in entries) {
                // Taken out while it waited: never started
                if (entry.job.isCancelled) continue
                withContext(stateContext) { running = entry.lecture }
                entry.job.start()
                entry.job.join()
                withContext(stateContext) { if (running === entry.lecture) running = null }
            }
        }
    }

    /** Whether [lecture] is in the queue, running or waiting. */
    operator fun contains(lecture: Lecture): Boolean = lectures.any { it === lecture }

    /**
     * Adds [work], the transcription of [lecture], after the ones already asked. Called on
     * [stateContext].
     *
     * @return its job: not started until its turn, and cancelled to take it out of the queue.
     */
    fun enqueue(lecture: Lecture, work: suspend () -> Unit): Job {
        val job = scope.launch(start = CoroutineStart.LAZY) { work() }
        lectures = lectures + lecture
        job.invokeOnCompletion {
            scope.launch(stateContext) { lectures = lectures.filterNot { it === lecture } }
        }
        entries.trySend(Entry(lecture, job))
        return job
    }
}
