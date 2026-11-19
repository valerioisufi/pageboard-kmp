package io.github.valerioisufi.pageboard.canvas.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

public actual fun encodePng(bitmap: ImageBitmap): ByteArray? = try {
    Image.makeFromBitmap(bitmap.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes
} catch (_: Throwable) {
    null
}
