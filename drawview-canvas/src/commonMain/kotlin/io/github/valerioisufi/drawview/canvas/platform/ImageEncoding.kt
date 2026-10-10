package io.github.valerioisufi.drawview.canvas.platform

import androidx.compose.ui.graphics.ImageBitmap

/** [bitmap] encoded as PNG (the export of a page, the thumbnails kept on disk), or `null` when it cannot be. */
expect fun encodePng(bitmap: ImageBitmap): ByteArray?
