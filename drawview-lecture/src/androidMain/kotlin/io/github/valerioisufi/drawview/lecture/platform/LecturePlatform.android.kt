package io.github.valerioisufi.drawview.lecture.platform

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.ByteArrayOutputStream

actual fun environmentApiKey(): String? = null

// The files of the application are private to it
actual fun restrictToOwner(path: String) {}

actual fun encodeJpeg(bitmap: ImageBitmap, quality: Int): ByteArray? = try {
    val stream = ByteArrayOutputStream()
    if (bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.JPEG, quality, stream)) stream.toByteArray() else null
} catch (_: Throwable) {
    null
}
