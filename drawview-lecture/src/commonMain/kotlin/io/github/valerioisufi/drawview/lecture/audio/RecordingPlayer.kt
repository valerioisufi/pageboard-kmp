package io.github.valerioisufi.drawview.lecture.audio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plays the recording of a lecture from any position, at [SPEEDS] (faster without raising the
 * pitch, [TimeStretcher]), also while it is being recorded. One recording plays at a time in the
 * application: playing one pauses the others.
 *
 * The controls are called on [stateContext] (the UI thread), where the observable properties are
 * updated.
 */
public class RecordingPlayer internal constructor(
    private val recording: RecordingFile,
    private val outputFactory: () -> AudioOutput?,
    private val stateContext: CoroutineContext
) {
    public var isPlaying: Boolean by mutableStateOf(false)
        private set

    /** Position in the recording. */
    public var positionMs: Long by mutableLongStateOf(0L)
        private set

    /** Length of the recording (it grows while recording). */
    public var durationMs: Long by mutableLongStateOf(recordedMs())
        private set

    public var speed: Float by mutableFloatStateOf(1f)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** Plays from [fromMs] (by default where it stopped; from the start when it reached the end). */
    public fun play(fromMs: Long = positionMs) {
        if (current !== this) current?.pause()
        current = this
        job?.cancel()
        durationMs = recordedMs()
        val start = if (fromMs >= durationMs - END_TOLERANCE_MS) 0L else fromMs.coerceIn(0L, durationMs)
        positionMs = start
        isPlaying = true
        val speed = speed
        job = scope.launch { run(start, speed) }
    }

    public fun pause() {
        job?.cancel()
        job = null
        isPlaying = false
    }

    public fun toggle(): Unit = if (isPlaying) pause() else play()

    /** Goes to [ms], playing on from there if it was playing. */
    public fun seek(ms: Long) {
        durationMs = recordedMs()
        if (isPlaying) play(ms.coerceIn(0L, durationMs)) else positionMs = ms.coerceIn(0L, durationMs)
    }

    public fun skip(deltaMs: Long): Unit = seek(positionMs + deltaMs)

    /** The next speed of [SPEEDS], after the last one the first. */
    public fun nextSpeed() {
        speed = SPEEDS[(SPEEDS.indexOf(speed) + 1) % SPEEDS.size]
        if (isPlaying) play(positionMs)
    }

    /** Refreshes the length (the recording grows while recording). */
    public fun refresh() {
        durationMs = recordedMs()
    }

    private fun recordedMs(): Long = recording.durationMs()

    private suspend fun run(startMs: Long, speed: Float) {
        val output = outputFactory()
        // Stopped by the system (headphones unplugged, a call): paused, as the other players do
        val running = currentCoroutineContext()[Job]
        val onStopped = { scope.launch(stateContext) { if (job === running) pause() } }
        if (output == null || !withContext(Dispatchers.IO) { output.open { onStopped() } }) {
            withContext(stateContext) { isPlaying = false }
            return
        }
        var finished = false
        try {
            val stretcher = if (speed == 1f) null else TimeStretcher(speed)
            var sourceMs = startMs
            while (true) {
                currentCoroutineContext().ensureActive()
                val block = withContext(Dispatchers.IO) { recording.readSegment(sourceMs, BLOCK_MS) } ?: break
                if (block.isEmpty()) break
                sourceMs += samplesToMs(block.size.toLong())
                val audio = stretcher?.process(block) ?: block
                // In short pieces: the position follows, a pause is heard right away
                var offset = 0
                while (offset < audio.size) {
                    val count = minOf(PIECE_SAMPLES, audio.size - offset)
                    val piece = audio.copyOfRange(offset, offset + count)
                    withContext(Dispatchers.IO) { output.write(piece) }
                    offset += count
                    // Not played yet: what the output holds and the rest of the block
                    val waiting = output.pending + (audio.size - offset)
                    val position = sourceMs - (samplesToMs(waiting.toLong()) * speed).roundToLong()
                    withContext(stateContext) { positionMs = position.coerceAtLeast(startMs) }
                }
            }
            // The end of the recording: until it is played
            while (output.pending > 0) {
                delay(PIECE_MS)
                val position = sourceMs - (samplesToMs(output.pending.toLong()) * speed).roundToLong()
                withContext(stateContext) { positionMs = position }
            }
            finished = true
        } finally {
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) { output.close() }
                if (finished) {
                    withContext(stateContext) {
                        durationMs = recordedMs()
                        positionMs = durationMs
                        isPlaying = false
                    }
                }
            }
        }
    }

    public companion object {
        internal val SPEEDS = listOf(1f, 1.25f, 1.5f, 2f)

        /** Audio read from the file at a time. */
        private const val BLOCK_MS = 2_000L
        private const val PIECE_MS = 100L
        private const val PIECE_SAMPLES = (LECTURE_SAMPLE_RATE * PIECE_MS / 1000).toInt()

        /** A play at the very end starts again from the beginning. */
        private const val END_TOLERANCE_MS = 500L

        /** The recording playing in the application. */
        private var current: RecordingPlayer? = null
    }
}
