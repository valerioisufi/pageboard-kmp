package io.github.valerioisufi.drawview.sync.iroh

import io.github.valerioisufi.drawview.platform.JvmNativeLibraries

/** The iroh bridge on the desktop: the JNI functions of `native/iroh-bridge/src/jni.rs`. */
internal object IrohJni : IrohBridge {
    external override fun bind(secretKey: ByteArray, relays: String, token: String, alpn: String): Long
    external override fun lastError(): String
    external override fun address(endpoint: Long, timeoutMs: Long): String?
    external override fun id(endpoint: Long): String?
    external override fun networkChange(endpoint: Long)
    external override fun closeEndpoint(endpoint: Long)
    external override fun connect(endpoint: Long, address: String, timeoutMs: Long): Long
    external override fun accept(endpoint: Long, timeoutMs: Long): Long
    external override fun remoteId(connection: Long): String?
    external override fun pathKind(connection: Long): Int
    external override fun closeConnection(connection: Long)
    external override fun openStream(connection: Long): Long
    external override fun acceptStream(connection: Long, timeoutMs: Long): Long
    external override fun send(stream: Long, frame: ByteArray, timeoutMs: Long): Int
    external override fun receive(stream: Long, timeoutMs: Long, maxSize: Int): ByteArray?
    external override fun isEnded(stream: Long): Boolean
    external override fun closeStream(stream: Long)
}

/** `libiroh_bridge` from the resources of the jar (see `drawview.jvm.natives`). */
internal actual fun loadIrohBridge(): IrohBridge? =
    if (JvmNativeLibraries.load(IrohJni::class.java, "iroh_bridge")) IrohJni else null
