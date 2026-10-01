package io.github.valerioisufi.drawview.core.util

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Multiplatform lock object for thread-safe memory caches and registries.
 *
 * Backed by `java.lang.Object` monitors on the JVM/Android, by a real mutex on Kotlin/Native
 * (tile rasterization runs on background threads on iOS too) and by a no-op on single-threaded
 * JS/Wasm. The atomicfu lock is wrapped (instead of aliased) so that the shared source sets of
 * the other modules do not depend on its platform-specific declarations.
 */
class SyncLock {
    @PublishedApi
    internal val monitor: SynchronizedObject = SynchronizedObject()
}

/**
 * Executes [block] while holding [lock] (reentrant).
 */
inline fun <R> syncLock(lock: SyncLock, block: () -> R): R = synchronized(lock.monitor, block)
