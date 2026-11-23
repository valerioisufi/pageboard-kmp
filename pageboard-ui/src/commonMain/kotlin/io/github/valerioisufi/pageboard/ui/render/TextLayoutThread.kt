package io.github.valerioisufi.pageboard.ui.render

/**
 * Runs [block], which lays text out with the text engine of Compose, where that engine may run:
 * on iOS the thread of the interface (its styles are kept in one global cache that is not safe
 * between threads, and racing the interface stopped the application), waiting for it from another
 * thread; elsewhere in place. The caller holds no lock while it waits, so the thread of the
 * interface, which never waits for the others, cannot be blocked by it
 * (docs/plans/ios-stability.md).
 */
internal expect fun <T> onTextLayoutThread(block: () -> T): T
