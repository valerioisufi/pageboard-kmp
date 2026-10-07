package io.github.valerioisufi.drawview.export

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.ByteArrayOutputStream

internal actual fun encodePng(bitmap: ImageBitmap): ByteArray? = try {
    ByteArrayOutputStream().use { output ->
        if (bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)) output.toByteArray() else null
    }
} catch (_: Throwable) {
    null
}
