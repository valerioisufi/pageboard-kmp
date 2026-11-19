package io.github.valerioisufi.pageboard.canvas.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlin.math.max
import kotlin.math.roundToInt
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

public actual fun decodeImageBitmap(bytes: ByteArray, maxDimension: Int): ImageBitmap? {
    return try {
        val image = Image.makeFromEncoded(bytes)
        val largest = max(image.width, image.height)
        if (largest <= maxDimension || largest <= 0) {
            image.toComposeImageBitmap()
        } else {
            val scale = maxDimension.toFloat() / largest
            val w = (image.width * scale).roundToInt().coerceAtLeast(1)
            val h = (image.height * scale).roundToInt().coerceAtLeast(1)
            val surface = Surface.makeRasterN32Premul(w, h)
            surface.canvas.drawImageRect(
                image,
                Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                Rect.makeWH(w.toFloat(), h.toFloat()),
                Paint()
            )
            surface.makeImageSnapshot().toComposeImageBitmap()
        }
    } catch (_: Throwable) {
        null
    }
}
