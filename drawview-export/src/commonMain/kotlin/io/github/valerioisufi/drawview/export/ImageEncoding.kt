package io.github.valerioisufi.drawview.export

import androidx.compose.ui.graphics.ImageBitmap

/** [bitmap] encoded as PNG, or `null` when it cannot be. */
internal expect fun encodePng(bitmap: ImageBitmap): ByteArray?
