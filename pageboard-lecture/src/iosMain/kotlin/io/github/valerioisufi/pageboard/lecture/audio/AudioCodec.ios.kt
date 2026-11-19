@file:OptIn(ExperimentalForeignApi::class)

package io.github.valerioisufi.pageboard.lecture.audio

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.AudioToolbox.ExtAudioFileDispose
import platform.AudioToolbox.ExtAudioFileOpenURL
import platform.AudioToolbox.ExtAudioFileRead
import platform.AudioToolbox.ExtAudioFileRefVar
import platform.AudioToolbox.ExtAudioFileSetProperty
import platform.AudioToolbox.kExtAudioFileProperty_ClientDataFormat
import platform.CoreAudioTypes.AudioBufferList
import platform.CoreAudioTypes.AudioStreamBasicDescription
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreAudioTypes.kLinearPCMFormatFlagIsPacked
import platform.CoreAudioTypes.kLinearPCMFormatFlagIsSignedInteger
import platform.CoreFoundation.CFURLRef
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSURL

/**
 * The audio files of other applications (m4a, mp3, wav, aiff, caf, flac...) decoded by Audio
 * Toolbox into the audio of the lectures (as on macOS, see `native/bridge/audio_codec_mac.c`).
 */
internal object AudioToolboxCodec : AudioCodec {
    private const val BLOCK_FRAMES = 16_384

    override fun decode(input: String, output: String): Long = memScoped {
        val file = alloc<ExtAudioFileRefVar>()
        val url = CFBridgingRetain(NSURL.fileURLWithPath(input)) as CFURLRef?
        val opened = ExtAudioFileOpenURL(url, file.ptr)
        platform.CoreFoundation.CFRelease(url)
        if (opened != 0) return -1
        try {
            // 16-bit mono PCM at 16 kHz, converted by Audio Toolbox
            val client = alloc<AudioStreamBasicDescription>().apply {
                mSampleRate = LECTURE_SAMPLE_RATE.toDouble()
                mFormatID = kAudioFormatLinearPCM
                mFormatFlags = kLinearPCMFormatFlagIsSignedInteger or kLinearPCMFormatFlagIsPacked
                mBitsPerChannel = 16u
                mChannelsPerFrame = 1u
                mBytesPerFrame = 2u
                mFramesPerPacket = 1u
                mBytesPerPacket = 2u
            }
            if (ExtAudioFileSetProperty(file.value, kExtAudioFileProperty_ClientDataFormat, sizeOf<AudioStreamBasicDescription>().toUInt(), client.ptr) != 0) return -1
            val samples = ShortArray(BLOCK_FRAMES)
            val list = alloc<AudioBufferList>()
            val frames = alloc<UIntVar>()
            var total = 0L
            SystemFileSystem.sink(Path(output)).buffered().use { sink ->
                while (true) {
                    val read = samples.usePinned { pinned ->
                        list.mNumberBuffers = 1u
                        list.mBuffers[0].mNumberChannels = 1u
                        list.mBuffers[0].mDataByteSize = (BLOCK_FRAMES * 2).toUInt()
                        list.mBuffers[0].mData = pinned.addressOf(0)
                        frames.value = BLOCK_FRAMES.toUInt()
                        if (ExtAudioFileRead(file.value, frames.ptr, list.ptr) != 0) -1 else frames.value.toInt()
                    }
                    if (read < 0) return -1
                    if (read == 0) break
                    sink.write(samples.copyOf(read).toLittleEndianBytes())
                    total += read
                }
            }
            total
        } finally {
            ExtAudioFileDispose(file.value)
        }
    }
}

internal actual fun audioCodec(): AudioCodec? = AudioToolboxCodec
