package io.github.valerioisufi.drawview.sync.protocol

/**
 * The synchronization of DrawView, whose API and protocol are still changing: an application that
 * starts the sync (`SyncEngine`, `LanSync`, `IrohSync`, `IrohInternet`, `Collaboration`) opts in,
 * knowing that a minor version may change them, and that devices on different versions of the
 * protocol cannot sync.
 */
@RequiresOptIn(
    message = "The sync of DrawView is experimental: its API and its protocol may change in a minor version.",
    level = RequiresOptIn.Level.WARNING
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CONSTRUCTOR)
public annotation class ExperimentalSyncApi
