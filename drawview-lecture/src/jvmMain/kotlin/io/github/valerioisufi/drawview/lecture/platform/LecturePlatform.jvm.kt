package io.github.valerioisufi.drawview.lecture.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.File

internal actual val isComputer: Boolean = true

// The access to the microphone is the one of the application launching the JVM
@Composable
internal actual fun rememberMicrophoneAccess(): suspend () -> Boolean = remember { suspend { true } }

actual fun environmentApiKey(): String? = System.getenv("GEMINI_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }

actual fun restrictToOwner(path: String) {
    val file = File(path)
    if (!file.exists()) return
    file.setReadable(false, false)
    file.setWritable(false, false)
    file.setReadable(true, true)
    file.setWritable(true, true)
}

// The panel and the banner of the editor tell it
internal actual fun showNotification(channel: String, title: String, text: String) {}
