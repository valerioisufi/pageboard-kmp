package io.github.valerioisufi.pageboard.sync.iroh

import android.content.Context
import android.util.Log
import io.github.valerioisufi.pageboard.platform.DrawViewAndroid

/** The iroh bridge on Android: the JNI functions of `native/iroh-bridge/src/jni.rs`. */
internal object IrohJni : IrohBridge {
    /** Gives iroh the Java VM and the application context, before any endpoint. */
    external fun initAndroid(context: Context)

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

private const val TAG = "IrohBridge"

/**
 * `libiroh_bridge.so` (packaged by `pageboard-native-iroh`), given the application context: iroh
 * reads the DNS servers of the system and the network interfaces through it.
 */
internal actual fun loadIrohBridge(): IrohBridge? = try {
    System.loadLibrary("iroh_bridge")
    if (DrawViewAndroid.isInitialized) {
        IrohJni.initAndroid(DrawViewAndroid.context)
    } else {
        Log.w(TAG, "DrawViewAndroid.init(context) was not called: iroh cannot read the DNS servers of the system")
    }
    IrohJni
} catch (e: UnsatisfiedLinkError) {
    Log.e(TAG, "Failed loading libiroh_bridge.so: no synchronization over the internet", e)
    null
}
