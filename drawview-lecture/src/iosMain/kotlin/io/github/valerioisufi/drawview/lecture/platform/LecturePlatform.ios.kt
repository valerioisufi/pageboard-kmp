package io.github.valerioisufi.drawview.lecture.platform

actual fun environmentApiKey(): String? = null

// The files of the application are private to it
actual fun restrictToOwner(path: String) {}
