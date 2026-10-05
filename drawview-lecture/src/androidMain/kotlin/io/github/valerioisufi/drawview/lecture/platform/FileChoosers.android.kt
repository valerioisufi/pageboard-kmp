package io.github.valerioisufi.drawview.lecture.platform

// Not available yet: the recordings are imported and exported on the desktop
internal actual val canChooseFiles: Boolean = false

internal actual fun chooseFileToOpen(title: String, extensions: Set<String>): ChosenFile? = null

internal actual fun chooseFileToSave(title: String, suggestedName: String): String? = null
