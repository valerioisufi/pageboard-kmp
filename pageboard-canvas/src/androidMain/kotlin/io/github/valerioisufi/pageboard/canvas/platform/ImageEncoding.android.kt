package io.github.valerioisufi.pageboard.canvas.platform

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.ByteArrayOutputStream

public actual fun encodePng(bitmap: ImageBitmap): ByteArray? = try {
    ByteArrayOutputStream().use { output ->
        if (bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)) output.toByteArray() else null
    }
} catch (_: Throwable) {
    null
}
