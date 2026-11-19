package io.github.valerioisufi.drawview.lecture.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

/** Asks for the permission to use the microphone where needed: whether it is allowed. */
@Composable
internal expect fun rememberMicrophoneAccess(): suspend () -> Boolean

/** Encodes [bitmap] as JPEG ([quality] 0-100), or `null` on failure. */
internal expect fun encodeJpeg(bitmap: ImageBitmap, quality: Int = 82): ByteArray?
