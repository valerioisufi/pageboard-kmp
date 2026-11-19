package io.github.valerioisufi.drawview.ui.editor

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry

internal actual fun plainClipEntry(text: String): ClipEntry = ClipEntry(ClipData.newPlainText(null, text))
