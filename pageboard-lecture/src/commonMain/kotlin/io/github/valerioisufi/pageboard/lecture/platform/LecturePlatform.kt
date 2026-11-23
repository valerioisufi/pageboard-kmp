package io.github.valerioisufi.pageboard.lecture.platform

import io.github.valerioisufi.pageboard.platform.dataDirectory

/** Whether the microphone is the one of a computer (its access granted to the application launching it). */
public expect val isComputer: Boolean

/** The API key of the `GEMINI_API_KEY` environment variable (desktop), used when none is configured. */
internal expect fun environmentApiKey(): String?

/** Makes the file at [path] readable and writable by its owner only (where supported). */
internal expect fun restrictToOwner(path: String)

/**
 * Blocks the calling thread for [millis]: between the attempts of a file replaced while another
 * thread reads it, which Windows refuses (see `writeAtomically`).
 */
internal expect fun pauseThread(millis: Long)

/**
 * Directory of the lecture recordings and of the assistant settings: `lectures`, next to the
 * resources directory of the Pageboard data.
 */
internal fun lectureDataDirectory(): String = dataDirectory() + "/lectures"

/**
 * Tells the user something that arrived (a lecture from another device...) with a notification
 * of the system ([channel]: the kind of notifications, where the system names them): Android and
 * iOS; on the desktop the panel tells it.
 */
internal expect fun showNotification(channel: String, title: String, text: String)
