package io.github.valerioisufi.drawview.lecture.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WavTest {
    @Test
    fun headerDescribesMonoPcm16() {
        val header = Wav.header(dataBytes = 32_000)
        assertEquals(Wav.HEADER_SIZE, header.size)
        assertEquals("RIFF", header.copyOfRange(0, 4).decodeToString())
        assertEquals("WAVE", header.copyOfRange(8, 12).decodeToString())
        assertEquals("data", header.copyOfRange(36, 40).decodeToString())
        assertEquals(36 + 32_000, int32(header, 4))
        assertEquals(1, int16(header, 22)) // mono
        assertEquals(LECTURE_SAMPLE_RATE, int32(header, 24))
        assertEquals(LECTURE_SAMPLE_RATE * 2, int32(header, 28))
        assertEquals(16, int16(header, 34))
        assertEquals(32_000, int32(header, 40))
    }

    @Test
    fun samplesRoundTripThroughLittleEndianBytes() {
        val samples = shortArrayOf(0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE, 12345, -12345)
        val wav = Wav.encode(samples)
        assertEquals(Wav.HEADER_SIZE + samples.size * 2, wav.size)
        assertContentEquals(samples, wav.copyOfRange(Wav.HEADER_SIZE, wav.size).littleEndianToShorts())
    }

    private fun int32(bytes: ByteArray, offset: Int) =
        (0 until 4).sumOf { (bytes[offset + it].toInt() and 0xFF) shl (8 * it) }

    private fun int16(bytes: ByteArray, offset: Int) =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}

class PcmResamplerTest {
    @Test
    fun sameRateMonoIsCopied() {
        val input = ShortArray(1000) { (it % 200 - 100).toShort() }
        assertContentEquals(input, PcmResampler(LECTURE_SAMPLE_RATE, 1).process(input))
    }

    @Test
    fun downsamplingKeepsDurationAndFrequency() {
        val inputRate = 48_000
        val seconds = 2
        val frequency = 440.0
        val input = ShortArray(inputRate * seconds) { (8000 * sin(2 * PI * frequency * it / inputRate)).roundToInt().toShort() }
        val output = PcmResampler(inputRate, 1).process(input)

        assertTrue(abs(output.size - LECTURE_SAMPLE_RATE * seconds) <= 2, "size ${output.size}")
        // 2 zero crossings per period
        assertTrue(abs(zeroCrossings(output) - 2 * frequency * seconds) <= 4, "crossings ${zeroCrossings(output)}")
    }

    @Test
    fun stereoChannelsAreAveraged() {
        val frames = 4800
        // Opposite channels cancel each other
        val input = ShortArray(frames * 2) { if (it % 2 == 0) 4000 else -4000 }
        val output = PcmResampler(48_000, 2).process(input)
        assertTrue(output.isNotEmpty())
        assertTrue(output.all { it.toInt() == 0 })
    }

    @Test
    fun outputDoesNotDependOnTheBlockSize() {
        val inputRate = 44_100
        val random = Random(7)
        val input = ShortArray(inputRate) { (random.nextInt(-3000, 3000)).toShort() }
        val whole = PcmResampler(inputRate, 1).process(input)

        val streaming = PcmResampler(inputRate, 1)
        val pieces = mutableListOf<Short>()
        var offset = 0
        while (offset < input.size) {
            val size = minOf(random.nextInt(1, 3000), input.size - offset)
            pieces.addAll(streaming.process(input.copyOfRange(offset, offset + size)).toList())
            offset += size
        }
        assertContentEquals(whole, pieces.toShortArray())
    }

    private fun zeroCrossings(samples: ShortArray): Int =
        (1 until samples.size).count { (samples[it - 1] < 0) != (samples[it] < 0) }
}

class SpeechChunkerTest {
    private val rate = LECTURE_SAMPLE_RATE

    private fun speech(seconds: Double, random: Random = Random(1)) =
        ShortArray((seconds * rate).toInt()) { random.nextInt(-6000, 6000).toShort() }

    private fun silence(seconds: Double) = ShortArray((seconds * rate).toInt()) { ((it % 7) - 3).toShort() }

    private fun pushInBlocks(chunker: SpeechChunker, samples: ShortArray, block: Int = rate / 10): List<AudioChunk> {
        val chunks = mutableListOf<AudioChunk>()
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(samples.size, offset + block)
            chunks.addAll(chunker.push(samples.copyOfRange(offset, end)))
            offset = end
        }
        return chunks
    }

    @Test
    fun cutsAtThePauseAfterTheTarget() {
        val chunker = SpeechChunker(targetSeconds = 30, overlapSeconds = 5)
        val chunks = pushInBlocks(chunker, speech(10.0) + silence(1.0) + speech(22.0) + silence(1.0) + speech(5.0))
        assertEquals(1, chunks.size)
        val chunk = chunks.single()
        // Not at the first pause (10 s, before the target), in the middle of the second one (33-34 s)
        val seconds = chunk.endMs / 1000.0
        assertTrue(seconds in 33.0..34.0, "cut at $seconds s")
        assertEquals(0, chunk.overlapSamples)
        assertEquals(0L, chunk.startSample)
    }

    @Test
    fun cutsAtTheMaximumWithoutPauses() {
        val chunker = SpeechChunker(targetSeconds = 30, maxSeconds = 45, overlapSeconds = 5)
        val chunks = pushInBlocks(chunker, speech(50.0))
        assertEquals(1, chunks.size)
        assertEquals(45L * rate, chunks.single().endSample)
    }

    @Test
    fun chunksOverlapAndCoverTheWholeStream() {
        val random = Random(3)
        val input = speech(31.0, random) + silence(0.5) + speech(31.0, random) + silence(0.5) + speech(12.0, random)
        val chunker = SpeechChunker(targetSeconds = 30, overlapSeconds = 5)
        val chunks = pushInBlocks(chunker, input, block = 1234).toMutableList()
        chunks.add(assertNotNull(chunker.cutNow()))
        assertEquals(3, chunks.size)
        assertEquals(input.size.toLong(), chunker.totalSamples)

        for (i in 1 until chunks.size) {
            val previous = chunks[i - 1]
            val chunk = chunks[i]
            assertEquals(5 * rate, chunk.overlapSamples)
            assertEquals(previous.endSample, chunk.newStartSample)
            // The overlap repeats the end of the previous chunk
            assertContentEquals(
                previous.samples.copyOfRange(previous.samples.size - chunk.overlapSamples, previous.samples.size),
                chunk.samples.copyOfRange(0, chunk.overlapSamples)
            )
        }
        // Without the overlaps, the chunks are the input
        val rebuilt = chunks.flatMap { it.samples.copyOfRange(it.overlapSamples, it.samples.size).toList() }.toShortArray()
        assertContentEquals(input, rebuilt)
    }

    @Test
    fun cutNowNeedsSomeNewAudio() {
        val chunker = SpeechChunker(targetSeconds = 30)
        assertNull(chunker.cutNow())
        chunker.push(speech(0.5))
        assertNull(chunker.cutNow(minSeconds = 1f))
        chunker.push(speech(1.0))
        val chunk = assertNotNull(chunker.cutNow(minSeconds = 1f))
        assertEquals((1.5 * rate).toLong(), chunk.endSample)
        assertNull(chunker.cutNow(minSeconds = 1f))
    }
}

class SilenceTest {
    private val rate = LECTURE_SAMPLE_RATE

    private fun seconds(value: Double, sample: (Int) -> Int) = ShortArray((value * rate).toInt()) { sample(it).toShort() }

    @Test
    fun speechIsNotSilence() {
        val random = Random(1)
        assertFalse(isSilent(seconds(60.0) { random.nextInt(-6000, 6000) }))
        // A quiet lecturer far from the microphone, over a little room noise
        assertFalse(isSilent(seconds(60.0) { if ((it / rate) % 3 == 0) random.nextInt(-80, 80) else random.nextInt(-700, 700) }))
        // A lecture heard from far away (as recorded by a laptop): about -40 dBFS over a floor of -65 dBFS
        assertFalse(isSilent(seconds(60.0) { if ((it / (rate / 2)) % 4 == 0) random.nextInt(-15, 15) else random.nextInt(-450, 450) }))
        // Ten seconds of speech in a minute
        assertFalse(isSilent(seconds(10.0) { random.nextInt(-5000, 5000) } + seconds(50.0) { 0 }))
    }

    @Test
    fun quietRoomsAndHumsAreSilence() {
        val random = Random(2)
        assertTrue(isSilent(seconds(60.0) { 0 }))
        assertTrue(isSilent(seconds(60.0) { random.nextInt(-60, 60) }))
        // Steady hum of a projector
        assertTrue(isSilent(seconds(60.0) { (900 * sin(2 * PI * 120.0 * it / rate)).roundToInt() }))
        // A couple of words in a minute
        assertTrue(isSilent(seconds(1.5) { random.nextInt(-5000, 5000) } + seconds(58.5) { random.nextInt(-40, 40) }))
        // The overlap with the previous chunk is not considered
        val chunk = seconds(5.0) { random.nextInt(-6000, 6000) } + seconds(55.0) { 0 }
        assertTrue(isSilent(chunk, from = 5 * rate))
    }
}


class TimeStretcherTest {
    private val rate = LECTURE_SAMPLE_RATE

    private fun sine(seconds: Double, hz: Double) = ShortArray((seconds * rate).toInt()) { (8000 * sin(2 * PI * hz * it / rate)).roundToInt().toShort() }

    /** Crossings of zero upwards per second: the pitch of a sine. */
    private fun frequency(samples: ShortArray): Double {
        var crossings = 0
        for (i in 1 until samples.size) if (samples[i - 1] < 0 && samples[i] >= 0) crossings++
        return crossings.toDouble() * rate / samples.size
    }

    @Test
    fun fasterButAtTheSamePitch() {
        for (speed in listOf(1.25f, 1.5f, 2f)) {
            val stretcher = TimeStretcher(speed)
            val input = sine(4.0, 220.0)
            // In blocks of any size, as the player reads them
            val output = input.toList().chunked(3_333).flatMap { stretcher.process(it.toShortArray()).toList() }.toShortArray()
            val expected = input.size / speed
            assertTrue(abs(output.size - expected) < rate * 0.1, "speed $speed: ${output.size} samples for $expected")
            // Away from the fade-in of the first frame
            val steady = output.copyOfRange(rate / 10, output.size)
            assertTrue(abs(frequency(steady) - 220.0) < 5.0, "speed $speed: ${frequency(steady)} Hz")
        }
    }
}
