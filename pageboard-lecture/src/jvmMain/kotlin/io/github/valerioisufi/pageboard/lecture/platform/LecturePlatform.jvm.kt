package io.github.valerioisufi.pageboard.lecture.platform

import java.io.File

public actual val isComputer: Boolean = true

internal actual fun environmentApiKey(): String? = System.getenv("GEMINI_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }

internal actual fun restrictToOwner(path: String) {
    val file = File(path)
    if (!file.exists()) return
    file.setReadable(false, false)
    file.setWritable(false, false)
    file.setReadable(true, true)
    file.setWritable(true, true)
}

// The panel and the banner of the editor tell it
internal actual fun showNotification(channel: String, title: String, text: String) {}
