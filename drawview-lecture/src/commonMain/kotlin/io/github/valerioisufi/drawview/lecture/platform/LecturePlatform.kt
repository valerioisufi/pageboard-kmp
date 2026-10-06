package io.github.valerioisufi.drawview.lecture.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import io.github.valerioisufi.drawview.core.platform.dataDirectory

/**
 * Whether the lectures recorded here are followed by the models (Gemini, local models): the
 * desktop, for now. Elsewhere they are recorded only (see [io.github.valerioisufi.drawview.lecture.LectureAssistant.canGenerate]).
 */
internal expect fun generationSupported(): Boolean

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
