package io.github.valerioisufi.pageboard.lecture.transcription

/**
 * The zip of an encoder of whisper.cpp compiled for Core ML (`ggml-<model>-encoder.mlmodelc`), as the
 * authors of whisper.cpp publish it: checked and extracted next to the model, where whisper.cpp runs it
 * on the Neural Engine (docs/plans/transcription-queue.md, "The encoder on the Neural Engine").
 */
internal interface EncoderArchive {
    /** The SHA-256 of the file at [path], in lowercase hexadecimal. */
    fun sha256(path: String): String

    /**
     * Extracts the directory [name] of the zip at [zip] into [directory], as `directory/name`, in place of
     * one already there: first into `directory/name.part`, renamed when complete.
     *
     * The entries of `__MACOSX` are skipped; an entry outside [name], or going out of it, and more than
     * [maxBytes] extracted fail it, leaving nothing.
     */
    fun extract(zip: String, directory: String, name: String, maxBytes: Long)
}

/** The encoders of this platform: a Mac with Apple Silicon only (an Intel Mac has no Neural Engine), else `null`. */
internal expect fun encoderArchive(): EncoderArchive?
