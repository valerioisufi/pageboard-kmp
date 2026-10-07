package io.github.valerioisufi.drawview.lecture.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The audio of a lecture recorded on another device, read as it arrives (GRASP Pure Fabrication:
 * the recorder follows it as it follows a microphone): from its start, then what is added to
 * [recording] as it grows. Once the lecture is over and its audio read whole ([isOver] with the
 * samples there are), [onEnd] is called.
 */
internal class FollowedRecording(
    private val recording: RecordingFile,
    private val isOver: (samples: Long) -> Boolean,
    private val onEnd: () -> Unit
) : AudioCapture {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun start(onSamples: (ShortArray) -> Unit, onEvent: (CaptureEvent) -> Unit) {
        job = scope.launch {
            var position = 0L
            var unchangedSince = 0L
            while (isActive) {
                val total = recording.samples()
                if (total > position) {
                    val block = recording.readSamples(position, minOf(total - position, BLOCK_SAMPLES)) ?: ShortArray(0)
                    if (block.isNotEmpty()) {
                        position += block.size
                        onSamples(block)
                        unchangedSince = 0L
                        continue
                    }
                }
                // Over: read whole, or nothing more arrives
                if (isOver(position) || (unchangedSince >= GIVE_UP_MS && isOver(Long.MAX_VALUE))) {
                    onEnd()
                    return@launch
                }
                delay(POLL_MS)
                unchangedSince += POLL_MS
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        /** Read at a time: a few seconds (the recorder cuts its chunks as it does with the microphone). */
        const val BLOCK_SAMPLES = 5L * LECTURE_SAMPLE_RATE
        const val POLL_MS = 500L

        /** The lecture over, and nothing more of its audio arrived for this long: what there is is all. */
        const val GIVE_UP_MS = 60_000L
    }
}
