package io.github.valerioisufi.drawview.data.db

import io.github.valerioisufi.drawview.core.model.StrokePoint

/**
 * Binary serializer/deserializer for vector stroke input points.
 * Ensures fast persistence to SQLite BLOB columns and cross-platform fidelity.
 */
object StrokeInputCodec {
    private const val MAGIC_V1: Byte = 0x44 // 'D'
    private const val MAGIC_V2: Byte = 0x57 // 'W'

    fun encode(points: List<StrokePoint>): ByteArray {
        val size = points.size
        // 2 magic bytes + 4 bytes count + size * 20 bytes
        val buffer = ByteArray(6 + size * 20)
        buffer[0] = MAGIC_V1
        buffer[1] = MAGIC_V2
        writeInt(buffer, 2, size)

        var offset = 6
        for (p in points) {
            writeFloat(buffer, offset, p.x)
            writeFloat(buffer, offset + 4, p.y)
            writeFloat(buffer, offset + 8, p.pressure)
            writeLong(buffer, offset + 12, p.timestampMs)
            offset += 20
        }
        return buffer
    }

    fun decode(bytes: ByteArray): List<StrokePoint> {
        if (bytes.size < 6) return emptyList()

        if (bytes[0] == MAGIC_V1 && bytes[1] == MAGIC_V2) {
            val count = readInt(bytes, 2)
            if (count <= 0 || bytes.size < 6 + count * 20) return emptyList()

            val list = ArrayList<StrokePoint>(count)
            var offset = 6
            for (i in 0 until count) {
                val x = readFloat(bytes, offset)
                val y = readFloat(bytes, offset + 4)
                val pressure = readFloat(bytes, offset + 8)
                val timestamp = readLong(bytes, offset + 12)
                list.add(StrokePoint(x, y, pressure, timestamp))
                offset += 20
            }
            return list
        }

        // Fallback for raw point arrays
        if (bytes.size >= 20 && bytes.size % 20 == 0) {
            val count = bytes.size / 20
            val list = ArrayList<StrokePoint>(count)
            var offset = 0
            for (i in 0 until count) {
                val x = readFloat(bytes, offset)
                val y = readFloat(bytes, offset + 4)
                val pressure = readFloat(bytes, offset + 8)
                val timestamp = readLong(bytes, offset + 12)
                list.add(StrokePoint(x, y, pressure, timestamp))
                offset += 20
            }
            return list
        }

        return emptyList()
    }

    private fun writeInt(b: ByteArray, offset: Int, v: Int) {
        b[offset] = (v ushr 24).toByte()
        b[offset + 1] = (v ushr 16).toByte()
        b[offset + 2] = (v ushr 8).toByte()
        b[offset + 3] = v.toByte()
    }

    private fun readInt(b: ByteArray, offset: Int): Int {
        return ((b[offset].toInt() and 0xFF) shl 24) or
                ((b[offset + 1].toInt() and 0xFF) shl 16) or
                ((b[offset + 2].toInt() and 0xFF) shl 8) or
                (b[offset + 3].toInt() and 0xFF)
    }

    private fun writeFloat(b: ByteArray, offset: Int, v: Float) {
        writeInt(b, offset, v.toBits())
    }

    private fun readFloat(b: ByteArray, offset: Int): Float {
        return Float.fromBits(readInt(b, offset))
    }

    private fun writeLong(b: ByteArray, offset: Int, v: Long) {
        b[offset] = (v ushr 56).toByte()
        b[offset + 1] = (v ushr 48).toByte()
        b[offset + 2] = (v ushr 40).toByte()
        b[offset + 3] = (v ushr 32).toByte()
        b[offset + 4] = (v ushr 24).toByte()
        b[offset + 5] = (v ushr 16).toByte()
        b[offset + 6] = (v ushr 8).toByte()
        b[offset + 7] = v.toByte()
    }

    private fun readLong(b: ByteArray, offset: Int): Long {
        return ((b[offset].toLong() and 0xFF) shl 56) or
                ((b[offset + 1].toLong() and 0xFF) shl 48) or
                ((b[offset + 2].toLong() and 0xFF) shl 40) or
                ((b[offset + 3].toLong() and 0xFF) shl 32) or
                ((b[offset + 4].toLong() and 0xFF) shl 24) or
                ((b[offset + 5].toLong() and 0xFF) shl 16) or
                ((b[offset + 6].toLong() and 0xFF) shl 8) or
                (b[offset + 7].toLong() and 0xFF)
    }
}
