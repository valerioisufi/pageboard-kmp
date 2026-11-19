package io.github.valerioisufi.drawview.ui.editor

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import java.awt.datatransfer.StringSelection

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun plainClipEntry(text: String): ClipEntry = ClipEntry(StringSelection(text))
