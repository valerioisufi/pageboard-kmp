package io.github.valerioisufi.drawview.platform

/**
 * SHA-256 of contents, in hexadecimal: the files with the same content have the same hash (e.g.
 * an image imported twice, or received from another device, is kept once).
 */
class Sha256 {
    private val state = INITIAL.copyOf()
    private val block = ByteArray(64)
    private var blockSize = 0
    private var length = 0L
    private val words = IntArray(64)

    fun update(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset): Sha256 {
        var i = offset
        val end = offset + count
        length += count
        while (i < end) {
            val copied = minOf(64 - blockSize, end - i)
            bytes.copyInto(block, blockSize, i, i + copied)
            blockSize += copied
            i += copied
            if (blockSize == 64) {
                compress()
                blockSize = 0
            }
        }
        return this
    }

    /** The hash of what was given, in hexadecimal (the object is not usable afterwards). */
    fun digestHex(): String {
        val bits = length * 8
        update(byteArrayOf(0x80.toByte()))
        while (blockSize != 56) update(byteArrayOf(0))
        update(ByteArray(8) { (bits ushr (56 - 8 * it)).toByte() })
        return state.joinToString("") { it.toUInt().toString(16).padStart(8, '0') }
    }

    private fun compress() {
        for (t in 0 until 16) {
            words[t] = ((block[t * 4].toInt() and 0xFF) shl 24) or ((block[t * 4 + 1].toInt() and 0xFF) shl 16) or
                ((block[t * 4 + 2].toInt() and 0xFF) shl 8) or (block[t * 4 + 3].toInt() and 0xFF)
        }
        for (t in 16 until 64) {
            val s0 = words[t - 15].rotateRight(7) xor words[t - 15].rotateRight(18) xor (words[t - 15] ushr 3)
            val s1 = words[t - 2].rotateRight(17) xor words[t - 2].rotateRight(19) xor (words[t - 2] ushr 10)
            words[t] = words[t - 16] + s0 + words[t - 7] + s1
        }
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        for (t in 0 until 64) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val choice = (e and f) xor (e.inv() and g)
            val temp1 = h + s1 + choice + K[t] + words[t]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val majority = (a and b) xor (a and c) xor (b and c)
            val temp2 = s0 + majority
            h = g; g = f; f = e; e = d + temp1
            d = c; c = b; b = a; a = temp1 + temp2
        }
        state[0] += a; state[1] += b; state[2] += c; state[3] += d
        state[4] += e; state[5] += f; state[6] += g; state[7] += h
    }

    companion object {
        /** The hash of [bytes], in hexadecimal. */
        fun of(bytes: ByteArray): String = Sha256().update(bytes).digestHex()

        private val INITIAL = intArrayOf(
            0x6a09e667, 0xbb67ae85L.toInt(), 0x3c6ef372, 0xa54ff53aL.toInt(),
            0x510e527f, 0x9b05688cL.toInt(), 0x1f83d9ab, 0x5be0cd19
        )

        private val K = intArrayOf(
            0x428a2f98, 0x71374491, 0xb5c0fbcfL.toInt(), 0xe9b5dba5L.toInt(),
            0x3956c25b, 0x59f111f1, 0x923f82a4L.toInt(), 0xab1c5ed5L.toInt(),
            0xd807aa98L.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3,
            0x72be5d74, 0x80deb1feL.toInt(), 0x9bdc06a7L.toInt(), 0xc19bf174L.toInt(),
            0xe49b69c1L.toInt(), 0xefbe4786L.toInt(), 0x0fc19dc6, 0x240ca1cc,
            0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152L.toInt(), 0xa831c66dL.toInt(), 0xb00327c8L.toInt(), 0xbf597fc7L.toInt(),
            0xc6e00bf3L.toInt(), 0xd5a79147L.toInt(), 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
            0x650a7354, 0x766a0abb, 0x81c2c92eL.toInt(), 0x92722c85L.toInt(),
            0xa2bfe8a1L.toInt(), 0xa81a664bL.toInt(), 0xc24b8b70L.toInt(), 0xc76c51a3L.toInt(),
            0xd192e819L.toInt(), 0xd6990624L.toInt(), 0xf40e3585L.toInt(), 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
            0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814L.toInt(), 0x8cc70208L.toInt(),
            0x90befffaL.toInt(), 0xa4506cebL.toInt(), 0xbef9a3f7L.toInt(), 0xc67178f2L.toInt()
        )
    }
}
