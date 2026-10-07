package io.github.valerioisufi.drawview.lecture.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import io.github.valerioisufi.drawview.core.platform.dataDirectory

/** Whether the microphone is the one of a computer (its access granted to the application launching it). */
internal expect val isComputer: Boolean

/** Asks for the permission to use the microphone where needed: whether it is allowed. */
@Composable
internal expect fun rememberMicrophoneAccess(): suspend () -> Boolean

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
fun lectureDataDirectory(): String = dataDirectory() + "/lectures"

/**
 * Tells the user something that arrived (a lecture from another device...) with a notification
 * of the system ([channel]: the kind of notifications, where the system names them): Android and
 * iOS; on the desktop the panel tells it.
 */
internal expect fun showNotification(channel: String, title: String, text: String)
