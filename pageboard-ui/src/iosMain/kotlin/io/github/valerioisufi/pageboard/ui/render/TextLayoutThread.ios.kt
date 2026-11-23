package io.github.valerioisufi.pageboard.ui.render

import platform.Foundation.NSThread
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_sync

internal actual fun <T> onTextLayoutThread(block: () -> T): T {
    if (NSThread.isMainThread) return block()
    var result: Result<T>? = null
    dispatch_sync(dispatch_get_main_queue()) { result = runCatching(block) }
    return checkNotNull(result).getOrThrow()
}
