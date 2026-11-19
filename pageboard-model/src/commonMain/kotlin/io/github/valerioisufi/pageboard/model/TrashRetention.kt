package io.github.valerioisufi.pageboard.model

/**
 * How long the trash keeps what is deleted (the documents and the folders of a library, the pages
 * of a document) before it is deleted for good (see `docs/plans/trash.md`).
 */
public object TrashRetention {
    /** 30 days, in milliseconds. */
    public const val MILLIS: Long = 30L * 24 * 60 * 60 * 1000

    /** The time before which what went to the trash is deleted for good, at [now]. */
    public fun expiredBefore(now: Long): Long = now - MILLIS
}
