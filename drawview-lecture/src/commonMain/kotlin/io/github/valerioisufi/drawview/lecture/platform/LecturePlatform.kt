package io.github.valerioisufi.drawview.lecture.platform

import androidx.compose.ui.graphics.ImageBitmap
import io.github.valerioisufi.drawview.core.platform.PlatformFiles

/** The API key of the `GEMINI_API_KEY` environment variable (desktop), used when none is configured. */
expect fun environmentApiKey(): String?

/** Makes the file at [path] readable and writable by its owner only (where supported). */
expect fun restrictToOwner(path: String)

/** Encodes [bitmap] as JPEG ([quality] 0-100), or `null` on failure. */
expect fun encodeJpeg(bitmap: ImageBitmap, quality: Int = 82): ByteArray?

/**
 * Directory of the lecture recordings and of the assistant settings: `lectures`, next to the
 * resources directory of the DrawView data.
 */
fun lectureDataDirectory(): String =
    PlatformFiles.resourcesDirectory().trimEnd('/').substringBeforeLast('/') + "/lectures"
