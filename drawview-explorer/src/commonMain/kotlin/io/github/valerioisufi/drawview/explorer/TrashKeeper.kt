package io.github.valerioisufi.drawview.explorer

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.valerioisufi.drawview.platform.currentTimeMillis
import io.github.valerioisufi.drawview.store.DocumentCatalog
import io.github.valerioisufi.drawview.store.TrashRetention
import kotlinx.coroutines.delay

/** The time now of the library, in milliseconds: the days left in the trash, its purge (a fixed time in the screenshots). */
internal val LocalLibraryClock = staticCompositionLocalOf<() -> Long> { ::currentTimeMillis }

/**
 * Deletes for good what has been in the trash of [catalog] longer than [TrashRetention] (see
 * `docs/trash.md`): when the library starts, then once a day while it runs.
 *
 * @param clock the time now, in milliseconds.
 */
internal class TrashKeeper(
    private val catalog: DocumentCatalog,
    private val clock: () -> Long = ::currentTimeMillis
) {
    /** Deletes for good what expired by now. */
    suspend fun purgeNow() {
        catalog.purgeTrash(before = TrashRetention.expiredBefore(clock()))
    }

    /** Purges now and then every [intervalMillis], until cancelled. */
    suspend fun run(intervalMillis: Long = INTERVAL_MILLIS): Nothing {
        while (true) {
            purgeNow()
            delay(intervalMillis)
        }
    }

    companion object {
        /** A day. */
        const val INTERVAL_MILLIS: Long = 24L * 60 * 60 * 1000
    }
}
