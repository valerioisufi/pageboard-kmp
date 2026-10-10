package io.github.valerioisufi.drawview.core.platform

import io.github.valerioisufi.drawview.model.geometry.Matrix2D

/** Whether [data] starts like a JPEG file. */
fun isJpeg(data: ByteArray): Boolean =
    data.size > 3 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte()

/**
 * The orientation of the JPEG [data] in its Exif metadata (1 to 8, 1 when it has none): how its
 * pixels are turned to be shown (see [exifOrientation]). [decodeImageBitmap] turns them.
 */
fun jpegOrientation(data: ByteArray): Int {
    fun byte(i: Int) = data[i].toInt() and 0xFF
    var i = 2
    while (i + 3 < data.size && byte(i) == 0xFF) {
        val marker = byte(i + 1)
        // The metadata come before the data of the image
        if (marker == 0xDA || marker == 0xD9) return 1
        val length = (byte(i + 2) shl 8) or byte(i + 3)
        if (length < 2) return 1
        val start = i + 4
        if (marker == 0xE1 && start + 14 <= data.size && data.decodeToString(start, start + 4) == "Exif") {
            return orientationTag(data, start + 6, minOf(i + 2 + length, data.size))
        }
        i += 2 + length
    }
    return 1
}

/** The orientation tag of the TIFF structure of Exif from [tiff] (until [end]), 1 when missing. */
private fun orientationTag(data: ByteArray, tiff: Int, end: Int): Int {
    if (tiff + 8 > end) return 1
    val littleEndian = data[tiff] == 'I'.code.toByte()
    fun u16(i: Int): Int {
        val a = data[i].toInt() and 0xFF
        val b = data[i + 1].toInt() and 0xFF
        return if (littleEndian) a or (b shl 8) else (a shl 8) or b
    }
    fun u32(i: Int): Int = if (littleEndian) u16(i) or (u16(i + 2) shl 16) else (u16(i) shl 16) or u16(i + 2)
    val ifd = tiff + u32(tiff + 4)
    if (ifd < tiff || ifd + 2 > end) return 1
    val count = u16(ifd)
    for (entry in 0 until count) {
        val at = ifd + 2 + entry * 12
        if (at + 12 > end) return 1
        if (u16(at) == ORIENTATION_TAG) return u16(at + 8).takeIf { it in 1..8 } ?: 1
    }
    return 1
}

/**
 * From the unit square of an image as it is stored (its top-left corner at the origin) to the unit
 * square of the image as it is shown, turned or mirrored by the Exif [orientation].
 */
fun exifOrientation(orientation: Int): Matrix2D = when (orientation) {
    // Mirrored left to right
    2 -> Matrix2D(-1f, 0f, 0f, 1f, 1f, 0f)
    // Turned half a turn
    3 -> Matrix2D(-1f, 0f, 0f, -1f, 1f, 1f)
    // Mirrored top to bottom
    4 -> Matrix2D(1f, 0f, 0f, -1f, 0f, 1f)
    // Mirrored along the diagonal from the top-left corner
    5 -> Matrix2D(0f, 1f, 1f, 0f, 0f, 0f)
    // Turned a quarter clockwise
    6 -> Matrix2D(0f, 1f, -1f, 0f, 1f, 0f)
    // Mirrored along the diagonal from the top-right corner
    7 -> Matrix2D(0f, -1f, -1f, 0f, 1f, 1f)
    // Turned a quarter counterclockwise
    8 -> Matrix2D(0f, -1f, 1f, 0f, 0f, 1f)
    else -> Matrix2D()
}

private const val ORIENTATION_TAG = 0x0112
