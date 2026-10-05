package io.github.valerioisufi.drawview.lecture.platform

/** A file chosen by the student. */
class ChosenFile(val path: String, val name: String, val lastModified: Long?)

/** Whether files can be chosen here (the desktop). */
internal expect val canChooseFiles: Boolean

/** Asks for a file to open among [extensions]: `null` when cancelled. Modal. */
internal expect fun chooseFileToOpen(title: String, extensions: Set<String>): ChosenFile?

/** Asks where to save a file, suggesting [suggestedName]: its path, `null` when cancelled. Modal. */
internal expect fun chooseFileToSave(title: String, suggestedName: String): String?
