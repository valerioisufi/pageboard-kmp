package io.github.valerioisufi.pageboard.platform

/**
 * A declaration public for the other modules of Pageboard only: an application does not use it,
 * and it may change in any version. The libraries of Pageboard opt in through their convention
 * plugin; outside them, using one is an error.
 */
@RequiresOptIn(
    message = "Part of the API between the modules of Pageboard: it may change in any version",
    level = RequiresOptIn.Level.ERROR
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS
)
public annotation class InternalPageboardApi
