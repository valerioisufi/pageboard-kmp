package io.github.valerioisufi.drawview.canvas.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

actual fun decodeImageBitmap(bytes: ByteArray, maxDimension: Int): ImageBitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        var w = bounds.outWidth
        var h = bounds.outHeight
        while (w > maxDimension || h > maxDimension) {
            sampleSize *= 2
            w /= 2
            h /= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        // Turned by its Exif orientation, as Skia decodes it on the other platforms
        val orientation = if (isJpeg(bytes)) jpegOrientation(bytes) else 1
        if (orientation == 1) return bitmap.asImageBitmap()
        val m = exifOrientation(orientation)
        // Without its translation: the bitmap made starts at the origin
        val matrix = Matrix().apply { setValues(floatArrayOf(m.a, m.c, 0f, m.b, m.d, 0f, 0f, 0f, 1f)) }
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).asImageBitmap()
    } catch (_: Throwable) {
        null
    }
}
