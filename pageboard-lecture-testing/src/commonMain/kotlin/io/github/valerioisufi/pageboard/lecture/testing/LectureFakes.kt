package io.github.valerioisufi.pageboard.lecture.testing

import io.github.valerioisufi.pageboard.lecture.NotebookSnapshot
import io.github.valerioisufi.pageboard.lecture.NotebookSource
import io.github.valerioisufi.pageboard.lecture.PageContent
import io.github.valerioisufi.pageboard.lecture.PageImage
import io.github.valerioisufi.pageboard.lecture.SecretStorage
import io.github.valerioisufi.pageboard.lecture.SlideText
import io.github.valerioisufi.pageboard.lecture.audio.AudioCapture
import io.github.valerioisufi.pageboard.lecture.audio.CaptureEvent
import io.github.valerioisufi.pageboard.lecture.audio.LECTURE_SAMPLE_RATE
import kotlin.random.Random

// The microphone, the notebook and the secrets of the tests of the assistant and of its UI

/**
 * A microphone of the tests: it hears what [speak] says (noise, or silence) and tells the events of
 * the system ([event]: a call takes the microphone...).
 */
public class FakeMicrophone : AudioCapture {
    private var onSamples: ((ShortArray) -> Unit)? = null
    private var onEvent: ((CaptureEvent) -> Unit)? = null

    override fun start(onSamples: (ShortArray) -> Unit, onEvent: (CaptureEvent) -> Unit) {
        this.onSamples = onSamples
        this.onEvent = onEvent
    }

    override fun stop() {
        onSamples = null
        onEvent = null
    }

    /** The system takes the microphone (a call), or gives it back. */
    public fun event(event: CaptureEvent) {
        onEvent?.invoke(event)
    }

    /** Speaks for [seconds]: noise when [loud], otherwise silence (or a noise of [quietLevel]). */
    public fun speak(seconds: Double, loud: Boolean, random: Random, quietLevel: Int = 0) {
        val total = (seconds * LECTURE_SAMPLE_RATE).toInt()
        var sent = 0
        while (sent < total) {
            val size = minOf(LECTURE_SAMPLE_RATE / 10, total - sent)
            onSamples?.invoke(
                ShortArray(size) {
                    when {
                        loud -> random.nextInt(-6000, 6000).toShort()
                        quietLevel > 0 -> random.nextInt(-quietLevel, quietLevel).toShort()
                        else -> 0
                    }
                }
            )
            sent += size
        }
    }
}

/** A notebook of the tests: three pages, the second one visible, with the text of their slides. */
public class FakeNotebook : NotebookSource {
    override val documentTitle: String = "Analisi 1"
    override suspend fun strokeIds(): Set<String> = setOf("old")
    override suspend fun capture(knownStrokeIds: Set<String>, textPages: Int, imagePages: Int): NotebookSnapshot = NotebookSnapshot(
        documentTitle = documentTitle,
        pageCount = 3,
        visiblePageIndexes = listOf(1),
        pages = listOf(
            PageContent(1, pageId = "11", slide = SlideText(1, 0, "Limiti"), hasInk = true),
            PageContent(2, pageId = "12", slide = SlideText(1, 1, "Derivate"))
        ),
        pageImages = listOf(PageImage(1, byteArrayOf(1, 2, 3))),
        strokeIds = setOf("old", "new"),
        pageIds = listOf("10", "11", "12")
    )
}

/** The secrets of the tests: in memory, never in the store of the system of the machine running them. */
public class MemorySecrets : SecretStorage {
    private val values = mutableMapOf<String, String>()

    override fun read(name: String): String? = values[name]

    override fun write(name: String, value: String?): Boolean {
        if (value == null) values.remove(name) else values[name] = value
        return true
    }
}
