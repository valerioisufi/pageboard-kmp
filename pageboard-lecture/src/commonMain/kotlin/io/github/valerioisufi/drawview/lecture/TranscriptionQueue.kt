package io.github.valerioisufi.drawview.lecture

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What stops a transcription between two of its pieces while something else needs the speech model
 * (a recording going on): see [RecordingTranscriber].
 */
internal interface TranscriptionGate {
    /** Whether the transcriptions wait now. */
    val isClosed: Boolean

    /** Suspends until the transcriptions may go on. */
    suspend fun awaitOpen()
}

/**
 * The transcriptions of whole recordings (an import, a lecture received without a transcript, a
 * lecture transcribed again), one at a time in the order they were asked (Pure Fabrication): a
 * transcription takes the speech model and minutes of the computer, so the others wait their turn
 * instead of being refused (see `docs/plans/transcription-queue.md`).
 *
 * A recording holds the queue ([hold], [release]): the transcription running stops at the end of the
 * piece it is on and frees its model ([gate]), no other one starts, and all goes on when the recording
 * is over. One speech model is in memory at a time.
 *
 * The queue is in memory. Its state ([lectures], [running]) is read and written on [stateContext];
 * the work runs in [scope].
 */
internal class TranscriptionQueue(private val scope: CoroutineScope, private val stateContext: CoroutineContext) {
    private class Entry(val lecture: Lecture, val job: Job)

    private val entries = Channel<Entry>(Channel.UNLIMITED)
    private val open = MutableStateFlow(true)
    private var holds = 0

    /** Closed while the queue is held: given to the transcriptions, which wait at it between two pieces. */
    val gate: TranscriptionGate = object : TranscriptionGate {
        override val isClosed: Boolean get() = !open.value
        override suspend fun awaitOpen() {
            open.first { it }
        }
    }

    /** Holds the queue until [release]: nothing is transcribed meanwhile. Called on [stateContext]. */
    fun hold() {
        holds++
        open.value = false
    }

    /** Lets the queue go on, once everything that held it released it. Called on [stateContext]. */
    fun release() {
        if (holds > 0 && --holds == 0) open.value = true
    }

    /** The lectures in the queue, in their order: the one being transcribed first, then those waiting. */
    var lectures: List<Lecture> by mutableStateOf(emptyList())
        private set

    /** The lecture whose transcription runs now, if any. */
    var running: Lecture? by mutableStateOf(null)
        private set

    init {
        scope.launch {
            for (entry in entries) {
                open.first { it }
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
