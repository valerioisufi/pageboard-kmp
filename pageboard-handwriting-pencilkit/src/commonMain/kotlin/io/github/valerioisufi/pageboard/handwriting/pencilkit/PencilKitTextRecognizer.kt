package io.github.valerioisufi.pageboard.handwriting.pencilkit

import io.github.valerioisufi.pageboard.handwriting.InkReading
import io.github.valerioisufi.pageboard.handwriting.InkSample
import io.github.valerioisufi.pageboard.handwriting.TextInkRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The handwriting recognizer of PencilKit (`PKStrokeRecognizer`) as a reader of text, on iOS, iPadOS
 * and macOS 27 and later: a block of samples given whole (the recognizer splits it into lines
 * itself), read on the device with the models of the system.
 *
 * The system reads only for an application in the foreground, launched as an application: the
 * languages are those of the system while it reads (checked by reading a word of its own, at most
 * once a minute), none otherwise (in the background, from a process started in a terminal, before
 * the systems 27, on the other platforms). A block that was not read is read again when the
 * languages come back.
 */
public class PencilKitTextRecognizer : TextInkRecognizer {
    override val version: String by lazy { "PencilKit ${pencilKitVersion()}" }

    override val readsBlocks: Boolean get() = true

    private val supported: Set<String> by lazy { parseLanguages(pencilKitLanguages()) }

    private val availability = Availability(
        check = { pencilKitAvailable(CHECK_TIMEOUT_MS) },
        whileAvailable = 60.seconds,
        whileUnavailable = 5.seconds
    )

    override suspend fun languages(): Set<String> = withContext(Dispatchers.IO) {
        if (supported.isEmpty() || !availability.isAvailable()) emptySet() else supported
    }

    override suspend fun recognize(ink: InkSample, language: String, context: String): InkReading? = withContext(Dispatchers.IO) {
        if (ink.isEmpty) return@withContext null
        val read = pencilKitRecognize(language, PencilKitSamples.of(ink), READ_TIMEOUT_MS)
        if (read == null) {
            // Nothing read: a drawing, or the system stopped reading (the application left the
            // foreground); the next languages() checks again
            availability.forget()
            return@withContext null
        }
        readingOf(read.first, read.second)
    }

    internal companion object {
        /** Points a millimeter: the size of the writing on an iPad (132 points an inch). */
        const val POINTS_PER_MM = 5.2f

        /** The longest wait for a block. */
        const val READ_TIMEOUT_MS = 10_000

        /** The longest wait for the word read to know whether the system reads. */
        const val CHECK_TIMEOUT_MS = 3_000

        /** The other readings given at most. */
        const val ALTERNATIVES = 5

        /** The languages of the bridge (BCP 47 tags split by commas). */
        fun parseLanguages(tags: String): Set<String> = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

        /**
         * The reading of [text] with the indexable content [search] of the recognizer: its other
         * lines are the other readings of a single line, those written as LaTeX (a formula read
         * as math) left out.
         */
        fun readingOf(text: String, search: String): InkReading {
            val reading = text.trim()
            val alternatives = if ('\n' in reading) {
                emptyList()
            } else {
                search.lines().map { it.trim() }
                    .filter { it.isNotEmpty() && it != reading && '\\' !in it }
                    .distinct()
                    .take(ALTERNATIVES)
            }
            return InkReading(reading, alternatives, search.trim().ifEmpty { reading })
        }
    }
}

/** The samples of a block as the bridge takes them: points, one clock, the samples of each stroke. */
internal class PencilKitSamples(
    val xy: FloatArray,
    val times: DoubleArray,
    val pressures: FloatArray,
    val sizes: IntArray
) {
    companion object {
        fun of(ink: InkSample): PencilKitSamples {
            val points = ink.strokes.flatten()
            val xy = FloatArray(points.size * 2)
            val times = DoubleArray(points.size)
            val pressures = FloatArray(points.size)
            points.forEachIndexed { i, p ->
                xy[i * 2] = p.x * PencilKitTextRecognizer.POINTS_PER_MM
                xy[i * 2 + 1] = p.y * PencilKitTextRecognizer.POINTS_PER_MM
                times[i] = p.timestampMs.toDouble()
                pressures[i] = if (p.pressure in 0f..1f) p.pressure else 0.5f
            }
            return PencilKitSamples(xy, times, pressures, ink.strokes.map { it.size }.toIntArray())
        }
    }
}

/** Whether the system reads now, checked by [check] and kept a while: longer while it reads. */
internal class Availability(
    private val check: () -> Boolean,
    private val whileAvailable: Duration,
    private val whileUnavailable: Duration,
    private val time: TimeSource = TimeSource.Monotonic
) {
    private val mutex = Mutex()
    private var known: Pair<Boolean, TimeMark>? = null

    suspend fun isAvailable(): Boolean = mutex.withLock {
        val (available, at) = known ?: (check() to time.markNow()).also { known = it }
        if (at.elapsedNow() < (if (available) whileAvailable else whileUnavailable)) {
            available
        } else {
            check().also { known = it to time.markNow() }
        }
    }

    suspend fun forget() {
        mutex.withLock { known = null }
    }
}

/** The version of the recognition of the system, 0 without a recognizer. */
internal expect fun pencilKitVersion(): Int

/** The languages of the recognizer, BCP 47 tags split by commas (empty without a recognizer). */
internal expect fun pencilKitLanguages(): String

/** Whether the system reads now, by reading a word of the bridge. */
internal expect fun pencilKitAvailable(timeoutMs: Int): Boolean

/** The text and the indexable content of [samples] in [language], `null` when nothing was read. */
internal expect fun pencilKitRecognize(language: String, samples: PencilKitSamples, timeoutMs: Int): Pair<String, String>?
