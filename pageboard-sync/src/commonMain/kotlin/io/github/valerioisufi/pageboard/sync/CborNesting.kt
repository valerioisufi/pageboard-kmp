package io.github.valerioisufi.pageboard.sync

/**
 * Whether the CBOR of [bytes] nests its arrays, maps, tags and strings in parts [maxDepth] deep at
 * most, read without recursion. What another device sends is checked so before it is decoded: the
 * decoding of a deeper nesting (composite changes in composite changes, as an application of its
 * own could send) would run out of stack, which cannot be caught on every platform. Bytes that are
 * not CBOR are refused too (the decoding would refuse them).
 */
internal fun cborNestsWithin(bytes: ByteArray, maxDepth: Int): Boolean {
    // The items left in each container open, -1: until its break
    val open = LongArray(maxDepth)
    var depth = 0
    var i = 0

    // An item ended: the containers it was the last item of end too
    fun ended() {
        while (depth > 0 && open[depth - 1] > 0) {
            open[depth - 1]--
            if (open[depth - 1] > 0) return
            depth--
        }
    }

    while (i < bytes.size) {
        val initial = bytes[i++].toInt() and 0xFF
        if (initial == BREAK) {
            if (depth == 0 || open[depth - 1] != -1L) return false
            depth--
            ended()
            continue
        }
        val major = initial shr 5
        val info = initial and 0x1F
        val argument = when {
            info < 24 -> info.toLong()
            info <= 27 -> {
                val size = 1 shl (info - 24)
                if (i + size > bytes.size) return false
                var value = 0L
                repeat(size) { value = (value shl 8) or (bytes[i++].toLong() and 0xFF) }
                // More than the bytes there are, as a length: not CBOR of a frame
                if (value < 0) return false
                value
            }
            info == 31 && major in 2..5 -> -1L
            else -> return false
        }
        when (major) {
            // A number, a simple value, a float (its bytes read with the argument)
            0, 1, 7 -> ended()
            2, 3 -> when {
                argument < 0 -> if (depth == maxDepth) return false else open[depth++] = -1
                argument > bytes.size - i -> return false
                else -> {
                    i += argument.toInt()
                    ended()
                }
            }
            4, 5 -> when {
                argument == 0L -> ended()
                // Every item takes a byte at least
                argument > bytes.size - i -> return false
                depth == maxDepth -> return false
                else -> open[depth++] = if (argument < 0) -1L else if (major == 5) argument * 2 else argument
            }
            // A tag: the item after it
            6 -> if (depth == maxDepth) return false else open[depth++] = 1
        }
    }
    return true
}

private const val BREAK = 0xFF
