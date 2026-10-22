package io.github.valerioisufi.drawview.sync.iroh

/**
 * The functions of the iroh bridge (`native/iroh-bridge`, Rust), the same on every platform: JNI
 * on Android and the desktop, its C API on iOS. Endpoints, connections and streams are numbers;
 * a number closed or never given is refused.
 *
 * The functions giving a number give a positive one, or [TIMEOUT], [CLOSED] or [ERROR] (described
 * by [lastError], on the same thread). They block: they are called off the main thread, with a
 * timeout.
 *
 * The members are public, in an internal interface: the JNI functions are named after them, and
 * the names of internal members are mangled on the JVM.
 */
internal interface IrohBridge {
    /**
     * Binds an endpoint with [secretKey] (32 bytes), through [relays] (their addresses separated
     * by spaces, `n0` for the public relays of n0, empty for none) with their access [token]
     * (empty for none), answering the connections of [alpn].
     */
    fun bind(secretKey: ByteArray, relays: String, token: String, alpn: String): Long

    /** The message of the last [ERROR] of this thread. */
    fun lastError(): String

    /**
     * The address of [endpoint] (its identifier, then its relays and direct addresses, separated
     * by spaces), once it reached its home relay (at most [timeoutMs]); `null` when closed.
     */
    fun address(endpoint: Long, timeoutMs: Long): String?

    /** The identifier of [endpoint] (its public key, in hexadecimal). */
    fun id(endpoint: Long): String?

    /** Tells [endpoint] that the network changed (after the background, another Wi-Fi...). */
    fun networkChange(endpoint: Long)

    /** Closes [endpoint], its connections and their streams. */
    fun closeEndpoint(endpoint: Long)

    /** Connects [endpoint] to the endpoint at [address] (see [address]). */
    fun connect(endpoint: Long, address: String, timeoutMs: Long): Long

    /** The next connection another endpoint opened to [endpoint]. */
    fun accept(endpoint: Long, timeoutMs: Long): Long

    /** The identifier of the endpoint at the other end of [connection]. */
    fun remoteId(connection: Long): String?

    /** What [connection] goes through now: [PATH_NONE], [PATH_DIRECT] or [PATH_RELAY]. */
    fun pathKind(connection: Long): Int

    /** The round-trip time of [connection] now, in milliseconds. */
    fun pathRtt(connection: Long): Long

    /** Closes [connection] and its streams. */
    fun closeConnection(connection: Long)

    /** Opens a stream on [connection]: the other endpoint sees it with its first frame. */
    fun openStream(connection: Long): Long

    /** The next stream the other endpoint opened on [connection]. */
    fun acceptStream(connection: Long, timeoutMs: Long): Long

    /**
     * Queues [frame] on [stream]: [SENT], [SEND_TIMEOUT] when the queue stayed full ([frame] is
     * not queued), [SEND_CLOSED] or [SEND_ERROR].
     */
    fun send(stream: Long, frame: ByteArray, timeoutMs: Long): Int

    /**
     * The next frame of [stream], or `null`: a timeout, or the end of the stream when [isEnded]
     * says so (after every frame received before it).
     */
    fun receive(stream: Long, timeoutMs: Long): ByteArray?

    /** Whether a [receive] found [stream] ended (or [stream] is closed). */
    fun isEnded(stream: Long): Boolean

    /** Closes [stream]: the frames queued are sent, then it is finished; nothing more is read. */
    fun closeStream(stream: Long)

    companion object {
        const val TIMEOUT: Long = 0
        const val CLOSED: Long = -1
        const val ERROR: Long = -2

        const val SENT: Int = 0
        const val SEND_TIMEOUT: Int = 1
        const val SEND_CLOSED: Int = 2
        const val SEND_ERROR: Int = -1

        const val PATH_NONE: Int = 0
        const val PATH_DIRECT: Int = 1
        const val PATH_RELAY: Int = 2
    }
}

/** The bridge of this platform, `null` when its native library is missing. */
internal expect fun loadIrohBridge(): IrohBridge?

/** The bridge of this process, loaded once; `null` without its native library. */
internal val irohBridge: IrohBridge? by lazy { loadIrohBridge() }
