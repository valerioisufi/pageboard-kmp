package io.github.valerioisufi.drawview.lecture.audio

import kotlinx.atomicfu.atomic
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPCMFormatFloat32
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionAllowBluetoothHFP
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.setActive
import platform.Foundation.NSError
import platform.posix.usleep

actual fun createAudioCapture(): AudioCapture? = AvAudioCapture()

actual fun createAudioOutput(): AudioOutput? = AvAudioOutput()

/**
 * The audio session of the lectures: recording and playing, on the speaker (not the receiver),
 * also in the background (the application declares the background audio, so that a lecture goes
 * on with the screen locked).
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun activateSession(): String? = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    val session = AVAudioSession.sharedInstance()
    session.setCategory(
        AVAudioSessionCategoryPlayAndRecord,
        mode = AVAudioSessionModeDefault,
        options = AVAudioSessionCategoryOptionDefaultToSpeaker or AVAudioSessionCategoryOptionAllowBluetoothHFP,
        error = error.ptr
    )
    if (!session.setActive(true, error.ptr)) error.value?.localizedDescription ?: "the audio session cannot be opened" else null
}

/**
 * The microphone of iOS (AVAudioEngine): its first channel, in its format, converted to 16 kHz
 * mono by [PcmResampler].
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class AvAudioCapture : AudioCapture {
    private var engine: AVAudioEngine? = null
    private val running = atomic(false)

    override fun start(onSamples: (ShortArray) -> Unit) {
        check(!running.value) { "The capture is already running" }
        activateSession()?.let { throw AudioCaptureException(it) }
        val newEngine = AVAudioEngine()
        val input = newEngine.inputNode
        val format = input.outputFormatForBus(0u)
        val rate = format.sampleRate.toInt()
        // No microphone (or no access to it)
        if (rate <= 0 || format.channelCount == 0u) throw AudioCaptureException("No microphone available")
        val resampler = PcmResampler(rate, 1)
        running.value = true
        // ~100 ms blocks
        input.installTapOnBus(0u, bufferSize = (rate / 10).toUInt(), format = format) { buffer, _ ->
            if (!running.value || buffer == null) return@installTapOnBus
            val frames = buffer.frameLength.toInt()
            val channel = buffer.floatChannelData?.get(0) ?: return@installTapOnBus
            val samples = ShortArray(frames) { i -> (channel[i] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort() }
            val converted = resampler.process(samples, frames)
            if (converted.isNotEmpty()) onSamples(converted)
        }
        newEngine.prepare()
        val error = memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            if (newEngine.startAndReturnError(error.ptr)) null else error.value?.localizedDescription ?: "the microphone cannot be started"
        }
        if (error != null) {
            running.value = false
            input.removeTapOnBus(0u)
            throw AudioCaptureException(error)
        }
        engine = newEngine
    }

    override fun stop() {
        running.value = false
        engine?.let {
            it.inputNode.removeTapOnBus(0u)
            it.stop()
        }
        engine = null
    }
}

/**
 * The speakers of iOS: the blocks written are scheduled on an [AVAudioPlayerNode] (16 kHz mono,
 * converted by the engine), [write] waiting while a quarter of a second is waiting to be played.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class AvAudioOutput : AudioOutput {
    private var engine: AVAudioEngine? = null
    private var player: AVAudioPlayerNode? = null
    private val queued = atomic(0)
    private val format = AVAudioFormat(commonFormat = AVAudioPCMFormatFloat32, sampleRate = LECTURE_SAMPLE_RATE.toDouble(), channels = 1u, interleaved = false)

    override fun open(): Boolean {
        activateSession()?.let {
            println("DrawView: cannot play the recording: $it")
            return false
        }
        val newEngine = AVAudioEngine()
        val newPlayer = AVAudioPlayerNode()
        newEngine.attachNode(newPlayer)
        newEngine.connect(newPlayer, to = newEngine.mainMixerNode, format = format)
        newEngine.prepare()
        val started = memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            newEngine.startAndReturnError(error.ptr).also { if (!it) println("DrawView: cannot play the recording: ${error.value?.localizedDescription}") }
        }
        if (!started) return false
        newPlayer.play()
        queued.value = 0
        engine = newEngine
        player = newPlayer
        return true
    }

    override fun write(samples: ShortArray, count: Int) {
        // As much as a line of the desktop holds: a pause or a seek is heard right away
        while (player != null && queued.value > MAX_QUEUED) usleep(WAIT_MICROSECONDS)
        val current = player ?: return
        val buffer = AVAudioPCMBuffer(pCMFormat = format, frameCapacity = count.toUInt())
        buffer.frameLength = count.toUInt()
        val channel = buffer.floatChannelData?.get(0) ?: return
        for (i in 0 until count) channel[i] = samples[i] / 32768f
        queued.addAndGet(count)
        current.scheduleBuffer(buffer) { queued.addAndGet(-count) }
    }

    override val pending: Int get() = queued.value.coerceAtLeast(0)

    override fun close() {
        val current = player ?: return
        player = null
        current.stop()
        engine?.stop()
        engine = null
        queued.value = 0
    }

    private companion object {
        const val MAX_QUEUED = LECTURE_SAMPLE_RATE / 4
        const val WAIT_MICROSECONDS = 10_000u
    }
}
