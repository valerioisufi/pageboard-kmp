package io.github.valerioisufi.drawview.lecture.platform

/**
 * A file chosen by the student: on the phones a copy the application reads ([temporary], deleted
 * once read).
 */
public class ChosenFile(public val path: String, public val name: String, internal val lastModified: Long?, public val temporary: Boolean = false)
