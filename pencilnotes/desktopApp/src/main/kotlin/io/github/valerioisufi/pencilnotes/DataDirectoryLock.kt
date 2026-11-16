package io.github.valerioisufi.pencilnotes

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption

/**
 * The hold of one process on a data directory: the database of a directory has one writer, because
 * two would number their operations alike (see `docs/plans/agent-tools.md`). The system releases it when
 * the process ends, however it ends, so a crash leaves nothing to clean up.
 */
internal class DataDirectoryLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock
) : AutoCloseable {

    override fun close() {
        lock.release()
        channel.close()
    }

    companion object {
        /** The file locked, in the data directory; its content means nothing. */
        private const val LOCK_FILE = "application.lock"

        /** The hold on [directory] (created when missing), or `null` when another process has it. */
        fun acquire(directory: File): DataDirectoryLock? {
            directory.mkdirs()
            val channel = FileChannel.open(
                File(directory, LOCK_FILE).toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
            )
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                // This very process holds it already
                null
            }
            if (lock == null) {
                channel.close()
                return null
            }
            return DataDirectoryLock(channel, lock)
        }
    }
}
