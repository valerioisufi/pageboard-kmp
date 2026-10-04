package io.github.valerioisufi.drawview.lecture.platform

import java.io.File

actual fun environmentApiKey(): String? = System.getenv("GEMINI_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }

actual fun restrictToOwner(path: String) {
    val file = File(path)
    if (!file.exists()) return
    file.setReadable(false, false)
    file.setWritable(false, false)
    file.setReadable(true, true)
    file.setWritable(true, true)
}
