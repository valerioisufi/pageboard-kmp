package io.github.valerioisufi.pageboard.ui.editor

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun plainClipEntry(text: String): ClipEntry = ClipEntry.withPlainText(text)
