package io.github.valerioisufi.drawview.sync.iroh

import io.github.valerioisufi.drawview.sync.protocol.SyncChannel
import io.github.valerioisufi.drawview.sync.protocol.SyncException
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * The threads of the calls to the bridge, which block: one for each channel waiting for a frame
 * and each connection waiting for streams, more than the 64 of [Dispatchers.IO].
 */
internal val IrohDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(256)

/**
 * How long a call to the bridge blocks at most: a coroutine cancelled while it waits lets go
 * within it.
 */
internal const val SLICE_MS: Long = 250

/**
 * A stream of an iroh connection as a [SyncChannel]: the bridge keeps the frames whole (a length,
 * then the bytes). [onClose] runs once, when this side closes it.
 */
internal class IrohChannel(
    private val bridge: IrohBridge,
    private val stream: Long,
    private val onClose: () -> Unit = {}
) : SyncChannel {
    private val lock = SynchronizedObject()
    private var closed = false

    override suspend fun send(frame: ByteArray): Unit = withContext(IrohDispatcher) {
        while (true) {
            when (bridge.send(stream, frame, SLICE_MS)) {
                IrohBridge.SENT -> return@withContext
                // The other side reads slowly: the frames wait in the bridge
                IrohBridge.SEND_TIMEOUT -> ensureActive()
                IrohBridge.SEND_CLOSED -> throw SyncException("The connection is closed")
                else -> throw SyncException("The connection failed: ${bridge.lastError()}")
            }
        }
    }

    override suspend fun receive(): ByteArray? = withContext(IrohDispatcher) { next() }

    /** The next frame, `null` at the end of the stream; it lets go when its coroutine is cancelled. */
    private fun CoroutineScope.next(): ByteArray? {
        while (true) {
            bridge.receive(stream, SLICE_MS)?.let { return it }
            if (bridge.isEnded(stream)) return null
            ensureActive()
        }
    }

    override fun close() {
        val first = synchronized(lock) { !closed.also { closed = true } }
        if (!first) return
        bridge.closeStream(stream)
        onClose()
    }
}
