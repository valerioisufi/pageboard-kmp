package io.github.valerioisufi.pageboard.lecture.audio

/**
 * The Opus encoder of the recordings (libopus, see `native/bridge/audio_bridge.c`): frames of
 * mono 16-bit PCM at [sampleRate] (20 ms: [OPUS_FRAME_MS]), speech at [bitrate] bit/s.
 */
internal expect class OpusEncoder(sampleRate: Int, bitrate: Int) {
    /** The samples (at the sample rate) the decoder skips at the start. */
    val lookahead: Int

    /** One packet: the encoded [frame]. */
    fun encode(frame: ShortArray): ByteArray

    fun close()
}

/** The Opus decoder of the recordings: mono 16-bit PCM at [sampleRate]. */
internal expect class OpusDecoder(sampleRate: Int) {
    /** The samples of [packet] (`null`: a packet lost, guessed). */
    fun decode(packet: ByteArray?): ShortArray

    fun close()
}

/** The AAC-LC encoder of the exported recordings (vo-aacenc): frames of [AAC_FRAME_SAMPLES] mono samples. */
internal expect class AacEncoder(sampleRate: Int, bitrate: Int) {
    /** One raw AAC frame (no ADTS header), possibly empty at the start. */
    fun encode(frame: ShortArray): ByteArray

    fun close()
}

/** The length of the Opus frames of the recordings. */
internal const val OPUS_FRAME_MS = 20

/** The samples of an AAC frame. */
internal const val AAC_FRAME_SAMPLES = 1024

/** The largest Opus packet. */
internal const val MAX_OPUS_PACKET = 4000

/** The largest Opus frame decoded (120 ms at 48 kHz). */
internal const val MAX_OPUS_SAMPLES = 5760
