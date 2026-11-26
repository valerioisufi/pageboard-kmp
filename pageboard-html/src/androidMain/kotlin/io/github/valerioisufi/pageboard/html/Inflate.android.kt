package io.github.valerioisufi.pageboard.html

import java.util.zip.DataFormatException
import java.util.zip.Inflater

internal actual fun inflateRaw(input: ByteArray, size: Int): ByteArray? {
    val inflater = Inflater(true)
    try {
        // Without the header of zlib the inflater wants a byte more than the data
        inflater.setInput(input + 0)
        val out = ByteArray(size)
        var written = 0
        while (written < size) {
            val count = inflater.inflate(out, written, size - written)
            if (count == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break
            written += count
        }
        if (written != size) return null
        // More than it said it holds
        if (!inflater.finished() && inflater.inflate(ByteArray(1)) > 0) return null
        return out
    } catch (_: DataFormatException) {
        return null
    } finally {
        inflater.end()
    }
}
