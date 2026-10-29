package io.github.valerioisufi.drawview.ui.platform

import androidx.compose.runtime.Composable

/**
 * Where to write a file the user saves: [path], then [deliver] with the path written (on the
 * phones the file is written in the application, then given to the place the user chose).
 */
public class FileTarget(public val path: String, public val deliver: suspend (written: String) -> Unit = {})

/**
 * Remembers the chooser of where to save a file of [mimeType] (the dialog of the desktop, the
 * document pickers of the phones), named as suggested at launch: [onTarget] gets where to write
 * it, and is not called when cancelled.
 */
@Composable
public expect fun rememberFileSaver(title: String, mimeType: String, onTarget: (FileTarget) -> Unit): (suggestedName: String) -> Unit

/**
 * Remembers the sharing of a file of [mimeType] with the other applications (the share sheet of
 * the phones: mail, chats, print, Files): [onTarget] gets where to write it, and its delivery
 * opens the sheet. `null` where the system has no share sheet (the desktop).
 */
@Composable
public expect fun rememberFileSharer(title: String, mimeType: String, onTarget: (FileTarget) -> Unit): ((suggestedName: String) -> Unit)?

/**
 * What can be done with a file once written where the user chose: [show] it in its folder (the
 * Finder, the Explorer, the file manager), [open] it with the application of the system.
 */
public class FileReveal(public val show: (path: String) -> Unit, public val open: (path: String) -> Unit)

/** The file manager of the desktop, or `null` on the phones (the file was given to the system). */
public expect fun fileReveal(): FileReveal?
