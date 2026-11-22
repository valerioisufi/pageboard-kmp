package io.github.valerioisufi.pencilnotes

internal actual fun environmentGeminiKey(): String? = System.getenv("GEMINI_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }
