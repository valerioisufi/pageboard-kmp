package io.github.valerioisufi.pageboard.lecture.audio

import io.github.valerioisufi.pageboard.lecture.cinterop.dv_aac_encode
import io.github.valerioisufi.pageboard.lecture.cinterop.dv_aac_encoder_create
import io.github.valerioisufi.pageboard.lecture.cinterop.dv_aac_encoder_destroy
import io.github.valerioisufi.pageboard.lecture.cinterop.dv_opus_decode
import io.github.valerioisufi.pageboard.lecture.cinterop.dv_opus_decoder_create
import io.github.valerioisufi.pageboard.lecture.cinterop.dv_opus_decoder_destroy
import io.github.valerioisufi.pageboard.lecture.cinterop.dv_opus_encode
import io.github.valerioisufi.pageboard.lecture.cinterop.dv_opus_encoder_create
import io.github.valerioisufi.pageboard.lecture.cinterop.dv_opus_encoder_destroy
import io.github.valerioisufi.pageboard.lecture.cinterop.dv_opus_lookahead
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned

// The codecs of native/bridge/audio_bridge.c, linked statically (libpageboard_audio.a)

@OptIn(ExperimentalForeignApi::class)
internal actual class OpusEncoder actual constructor(sampleRate: Int, bitrate: Int) {
    private var handle: COpaquePointer? = checkNotNull(dv_opus_encoder_create(sampleRate, bitrate)) { "No Opus encoder" }
    private val buffer = ByteArray(MAX_OPUS_PACKET)

    actual val lookahead: Int = dv_opus_lookahead(handle)

    actual fun encode(frame: ShortArray): ByteArray {
        val written = frame.usePinned { pcm ->
            buffer.usePinned { out -> dv_opus_encode(handle, pcm.addressOf(0), frame.size, out.addressOf(0).reinterpret(), buffer.size) }
        }
        check(written >= 0) { "Opus encoding failed ($written)" }
        return buffer.copyOf(written)
    }

    actual fun close() {
        dv_opus_encoder_destroy(handle)
        handle = null
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual class OpusDecoder actual constructor(sampleRate: Int) {
    private var handle: COpaquePointer? = checkNotNull(dv_opus_decoder_create(sampleRate)) { "No Opus decoder" }
    private val buffer = ShortArray(MAX_OPUS_SAMPLES)

    actual fun decode(packet: ByteArray?): ShortArray {
        val decoded = buffer.usePinned { pcm ->
            if (packet == null || packet.isEmpty()) {
                dv_opus_decode(handle, null, 0, pcm.addressOf(0), buffer.size)
            } else {
                packet.usePinned { bytes -> dv_opus_decode(handle, bytes.addressOf(0).reinterpret(), packet.size, pcm.addressOf(0), buffer.size) }
            }
        }
        return if (decoded > 0) buffer.copyOf(decoded) else ShortArray(0)
    }

    actual fun close() {
        dv_opus_decoder_destroy(handle)
        handle = null
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual class AacEncoder actual constructor(sampleRate: Int, bitrate: Int) {
    private var handle: COpaquePointer? = checkNotNull(dv_aac_encoder_create(sampleRate, bitrate)) { "No AAC encoder" }
    private val buffer = ByteArray(8192)

    actual fun encode(frame: ShortArray): ByteArray {
        val written = frame.usePinned { pcm ->
            buffer.usePinned { out -> dv_aac_encode(handle, pcm.addressOf(0), out.addressOf(0).reinterpret(), buffer.size) }
        }
        check(written >= 0) { "AAC encoding failed" }
        return buffer.copyOf(written)
    }

    actual fun close() {
        dv_aac_encoder_destroy(handle)
        handle = null
    }
}
