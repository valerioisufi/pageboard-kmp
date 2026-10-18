package io.github.valerioisufi.drawview.platform

import android.annotation.SuppressLint
import android.content.Context
import java.io.File

/**
 * Holds the application [Context] needed by the Android implementations of DrawView
 * (files directory, database location, LaTeX resources).
 *
 * Must be initialized once, typically from `Application.onCreate()` or `Activity.onCreate()`.
 */
@SuppressLint("StaticFieldLeak")
public object DrawViewAndroid {
    private var appContext: Context? = null

    public fun init(context: Context) {
        appContext = context.applicationContext
    }

    public val context: Context
        get() = checkNotNull(appContext) {
            "DrawViewAndroid.init(context) must be called before using DrawView on Android"
        }

    public val isInitialized: Boolean get() = appContext != null
}

public actual object PlatformFiles {
    public actual fun resourcesDirectory(): String {
        val dir = File(DrawViewAndroid.context.filesDir, "drawview_resources")
        dir.mkdirs()
        return dir.absolutePath
    }

    public actual fun readBytes(path: String): ByteArray? = try {
        File(path).takeIf { it.exists() }?.readBytes()
    } catch (_: Exception) {
        null
    }

    public actual fun writeBytes(path: String, bytes: ByteArray): Boolean = try {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        true
    } catch (_: Exception) {
        false
    }

    public actual fun exists(path: String): Boolean = File(path).exists()

    public actual fun delete(path: String): Boolean = File(path).delete()

    public actual fun name(path: String): String = File(path).name

    public actual fun parent(path: String): String? = File(path).parent
}
