package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.lecture.audio.AudioOutput
import io.github.valerioisufi.drawview.lecture.audio.LECTURE_SAMPLE_RATE
import io.github.valerioisufi.drawview.lecture.audio.RecordingFile
import io.github.valerioisufi.drawview.lecture.audio.RecordingPlayer
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(DelicateCoroutinesApi::class)
class RecordingPlayerTest {
    private val directory: File = Files.createTempDirectory("player").toFile()
    private val ui = newSingleThreadContext("player-ui")

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
        ui.close()
    }

    /** An output that plays instantly, keeping what it receives. */
    private class FakeOutput : AudioOutput {
        val received = Collections.synchronizedList(mutableListOf<Short>())
        var closed = false
        override fun open() = true
        override fun write(samples: ShortArray, count: Int) {
            for (i in 0 until count) received.add(samples[i])
        }

        override val pending = 0
        override fun close() {
            closed = true
        }
    }

    /** 10 s of recording: the sample value is the second it belongs to. */
    private fun recording(): RecordingFile = RecordingFile(directory.absolutePath).apply {
        open()
        for (second in 0 until 10) append(ShortArray(LECTURE_SAMPLE_RATE) { second.toShort() })
        finish()
    }

    @Test
    fun theRecordingIsPlayedFromAnyPosition() = runBlocking<Unit> {
        val output = FakeOutput()
        val player = RecordingPlayer(recording(), { output }, ui)
        assertEquals(10_000L, player.durationMs)

        withContext(ui) { player.play(7_000) }
        withTimeout(10_000) { while (player.isPlaying) delay(10) }
        // From the eighth second to the end, then at the end
        assertEquals(3 * LECTURE_SAMPLE_RATE, output.received.size)
        assertEquals(7.toShort(), output.received.first())
        assertEquals(10_000L, player.positionMs)
        assertTrue(output.closed)

        // At the end: from the beginning again, twice as fast
        output.received.clear()
        withContext(ui) {
            player.nextSpeed()
            player.nextSpeed()
            player.nextSpeed()
        }
        assertEquals(2f, player.speed)
        withContext(ui) { player.play() }
        withTimeout(10_000) { while (player.isPlaying) delay(10) }
        val expected = 10 * LECTURE_SAMPLE_RATE / 2
        assertTrue(kotlin.math.abs(output.received.size - expected) < LECTURE_SAMPLE_RATE / 5, "${output.received.size} for $expected")
    }

    @Test
    fun aPauseStopsTheOutput() = runBlocking<Unit> {
        // An output as slow as real time
        val output = object : AudioOutput {
            var closed = false
            override fun open() = true
            override fun write(samples: ShortArray, count: Int) = Thread.sleep(count * 1000L / LECTURE_SAMPLE_RATE)
            override val pending = 0
            override fun close() {
                closed = true
            }
        }
        val player = RecordingPlayer(recording(), { output }, ui)
        withContext(ui) { player.play(0) }
        withTimeout(5_000) { while (player.positionMs < 300) delay(10) }
        withContext(ui) { player.pause() }
        withTimeout(5_000) { while (!output.closed) delay(10) }
        assertFalse(player.isPlaying)
        val stoppedAt = player.positionMs
        assertTrue(stoppedAt in 300L..2_000L, "$stoppedAt")
        delay(300)
        assertEquals(stoppedAt, player.positionMs)
        withContext(ui) { player.skip(-10_000) }
        assertEquals(0L, player.positionMs)
    }
}
