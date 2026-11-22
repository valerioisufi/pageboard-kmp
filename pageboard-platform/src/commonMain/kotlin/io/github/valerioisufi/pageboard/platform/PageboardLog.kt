package io.github.valerioisufi.pageboard.platform

/**
 * What the libraries of Pageboard tell about a failure they handled (a thumbnail not drawn, a file
 * not read): written to the log of the platform, which the application routes, never to the
 * standard output. A message names a document by its id, never by a name or a path.
 */
@InternalPageboardApi
public object PageboardLog {
    /** Writes [message] as a warning, with [error] when there is one. */
    public fun warn(message: String, error: Throwable? = null) {
        writeWarning(message, error)
    }
}

/** The log of the platform: Logcat on Android, a `System.Logger` on the desktop, `NSLog` on iOS. */
internal expect fun writeWarning(message: String, error: Throwable?)
