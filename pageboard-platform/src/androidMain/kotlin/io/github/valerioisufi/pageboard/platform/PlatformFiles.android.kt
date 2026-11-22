package io.github.valerioisufi.pageboard.platform

import android.annotation.SuppressLint
import android.content.Context
import java.io.File

/**
 * Holds the application [Context] needed by the Android implementations of Pageboard
 * (files directory, database location, LaTeX resources), the counterpart of [PageboardDesktop].
 *
 * Given once by the application, typically from `Application.onCreate()` or
 * `Activity.onCreate()`: the application context of the first call is kept, the same one again
 * does nothing, and another one fails.
 */
@SuppressLint("StaticFieldLeak")
public object PageboardAndroid {
    private var appContext: Context? = null

    public fun init(context: Context) {
        val application = context.applicationContext ?: context
        synchronized(this) {
            val current = appContext
            check(current == null || current === application) {
                "PageboardAndroid is given another context: an application gives its context once"
            }
            appContext = application
        }
    }

    public val context: Context
        get() = checkNotNull(synchronized(this) { appContext }) {
            "PageboardAndroid.init(context) must be called before using Pageboard on Android"
        }

    public val isInitialized: Boolean get() = synchronized(this) { appContext != null }
}

public actual object PlatformFiles {
    public actual fun resourcesDirectory(): String {
        val dir = File(PageboardAndroid.context.filesDir, "pageboard_resources")
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
