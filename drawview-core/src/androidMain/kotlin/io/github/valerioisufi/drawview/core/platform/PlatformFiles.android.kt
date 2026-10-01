package io.github.valerioisufi.drawview.core.platform

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

/**
 * Holds the application [Context] needed by the Android implementations of DrawView
 * (files directory, database location, LaTeX resources).
 *
 * Must be initialized once, typically from `Application.onCreate()` or `Activity.onCreate()`.
 */
@SuppressLint("StaticFieldLeak")
object DrawViewAndroid {
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val context: Context
        get() = checkNotNull(appContext) {
            "DrawViewAndroid.init(context) must be called before using DrawView on Android"
        }

    val isInitialized: Boolean get() = appContext != null
}

actual object PlatformFiles {
    actual fun resourcesDirectory(): String {
        val dir = File(DrawViewAndroid.context.filesDir, "drawview_resources")
        dir.mkdirs()
        return dir.absolutePath
    }

    actual fun readBytes(path: String): ByteArray? = try {
        File(path).takeIf { it.exists() }?.readBytes()
    } catch (_: Exception) {
        null
    }

    actual fun writeBytes(path: String, bytes: ByteArray): Boolean = try {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        true
    } catch (_: Exception) {
        false
    }

    actual fun exists(path: String): Boolean = File(path).exists()

    actual fun delete(path: String): Boolean = File(path).delete()
}

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
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    } catch (_: Throwable) {
        null
    }
}
