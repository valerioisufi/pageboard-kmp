package io.github.valerioisufi.drawview.canvas.render

import androidx.compose.ui.graphics.ImageBitmap
import io.github.valerioisufi.drawview.canvas.platform.decodeImageFile
import io.github.valerioisufi.drawview.platform.SyncLock
import io.github.valerioisufi.drawview.platform.syncLock

/**
 * The images of the resources decoded, kept by file for the ones drawn last, at most [maxBytes]
 * of pixels: the images of the documents are data, their pixels a cache of the renderers.
 * Thread-safe: the tiles are rasterized on background workers.
 */
class ImageCache(private val maxBytes: Long = 256L * 1024 * 1024) {
    private val lock = SyncLock()

    // Most recently used last
    private val images = LinkedHashMap<String, ImageBitmap>()
    private var bytes = 0L

    /** The pixels of the image file [path], decoded when they are not kept, or `null`. */
    fun load(path: String): ImageBitmap? {
        syncLock(lock) { images.remove(path)?.let { images[path] = it; return it } }
        // Decoded out of the lock: the other workers do not wait for it
        return decodeImageFile(path)?.also { put(path, it) }
    }

    /** Keeps [bitmap] as the pixels of the image file [path] (e.g. decoded while importing it). */
    fun put(path: String, bitmap: ImageBitmap) = syncLock(lock) {
        images.remove(path)?.let { bytes -= it.byteCount }
        images[path] = bitmap
        bytes += bitmap.byteCount
        while (bytes > maxBytes && images.size > 1) {
            val oldest = images.keys.first()
            images.remove(oldest)?.let { bytes -= it.byteCount }
        }
    }

    private val ImageBitmap.byteCount: Long get() = width.toLong() * height * 4

    companion object {
        /** The cache of the renderers of the process. */
        val Shared: ImageCache = ImageCache()
    }
}
