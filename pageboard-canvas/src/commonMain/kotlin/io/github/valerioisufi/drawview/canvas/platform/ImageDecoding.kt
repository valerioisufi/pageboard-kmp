package io.github.valerioisufi.drawview.canvas.platform

import androidx.compose.ui.graphics.ImageBitmap
import io.github.valerioisufi.drawview.platform.PlatformFiles

/**
 * Decodes an encoded image (PNG, JPEG, WebP, ...) downsampling it so that its largest side
 * does not exceed [maxDimension] pixels.
 */
public expect fun decodeImageBitmap(bytes: ByteArray, maxDimension: Int = 2048): ImageBitmap?

internal fun decodeImageFile(path: String, maxDimension: Int = 2048): ImageBitmap? =
    PlatformFiles.readBytes(path)?.let { decodeImageBitmap(it, maxDimension) }
