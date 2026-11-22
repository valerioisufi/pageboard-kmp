package io.github.valerioisufi.pageboard.platform

/** The logger of Pageboard: java.util.logging by default, or what the application gives (System.LoggerFinder). */
private val logger: System.Logger by lazy { System.getLogger("Pageboard") }

internal actual fun writeWarning(message: String, error: Throwable?) {
    if (error == null) logger.log(System.Logger.Level.WARNING, message)
    else logger.log(System.Logger.Level.WARNING, message, error)
}
