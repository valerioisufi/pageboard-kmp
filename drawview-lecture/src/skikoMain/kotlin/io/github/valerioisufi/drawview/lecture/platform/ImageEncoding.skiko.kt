package io.github.valerioisufi.drawview.lecture.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

actual fun encodeJpeg(bitmap: ImageBitmap, quality: Int): ByteArray? = try {
    Image.makeFromBitmap(bitmap.asSkiaBitmap()).encodeToData(EncodedImageFormat.JPEG, quality)?.bytes
} catch (_: Throwable) {
    null
}
