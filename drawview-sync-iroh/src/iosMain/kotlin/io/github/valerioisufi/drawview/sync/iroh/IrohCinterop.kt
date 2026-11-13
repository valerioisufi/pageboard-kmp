package io.github.valerioisufi.drawview.sync.iroh

import io.github.valerioisufi.drawview.sync.iroh.cinterop.DV_IROH_CLOSED
import io.github.valerioisufi.drawview.sync.iroh.cinterop.DV_IROH_ERROR
import io.github.valerioisufi.drawview.sync.iroh.cinterop.DV_IROH_OK
import io.github.valerioisufi.drawview.sync.iroh.cinterop.DV_IROH_TIMEOUT
import io.github.valerioisufi.drawview.sync.iroh.cinterop.DvIrohBytes
import io.github.valerioisufi.drawview.sync.iroh.cinterop.dv_iroh_accept
import io.github.valerioisufi.drawview.sync.iroh.cinterop.dv_iroh_accept_stream
import io.github.valerioisufi.drawview.sync.iroh.cinterop.dv_iroh_address
import io.github.valerioisufi.drawview.sync.iroh.cinterop.dv_iroh_bind
import io.github.valerioisufi.drawview.sync.iroh.cinterop.dv_iroh_bytes_free
import io.github.valerioisufi.drawview.sync.iroh.cinterop.dv_iroh_close_connection
import io.github.valerioisufi.drawview.sync.iroh.cinterop.dv_iroh_close_endpoint
import io.github.valerioisufi.drawview.sync.iroh.cinterop.dv_iroh_close_stream
import io.github.valerioisufi.drawview.sync.iroh.cinterop.dv_iroh_connect
import io.github.valerioisufi.drawview.sync.iroh.cinterop.dv_iroh_id
import io.github.valerioisufi.drawview.sync.iroh.cinterop.dv_iroh_is_ended
import io.github.valerioisufi.drawview.sync.iroh.cinterop.dv_iroh_last_error
import io.github.valerioisufi.drawview.sync.iroh.cinterop.dv_iroh_network_change
import io.github.valerioisufi.drawview.sync.iroh.cinterop.dv_iroh_open_stream
import io.github.valerioisufi.drawview.sync.iroh.cinterop.dv_iroh_path
import io.github.valerioisufi.drawview.sync.iroh.cinterop.dv_iroh_receive
import io.github.valerioisufi.drawview.sync.iroh.cinterop.dv_iroh_remote_id
import io.github.valerioisufi.drawview.sync.iroh.cinterop.dv_iroh_send
import io.github.valerioisufi.drawview.sync.iroh.cinterop.dv_iroh_string_free
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.readValue
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

/** The iroh bridge on iOS: the C API of `native/iroh-bridge` (`include/iroh_bridge.h`). */
@OptIn(ExperimentalForeignApi::class)
internal object IrohCinterop : IrohBridge {

    override fun bind(secretKey: ByteArray, relays: String, token: String, alpn: String): Long = memScoped {
        val endpoint = alloc<ULongVar>()
        val code = secretKey.usePinned { key ->
            dv_iroh_bind(key.addressOf(0).reinterpret(), secretKey.size.convert(), relays, token, alpn, endpoint.ptr)
        }
        number(code, endpoint.value)
    }

    override fun lastError(): String = dv_iroh_last_error()?.toKString().orEmpty()

    override fun address(endpoint: Long, timeoutMs: Long): String? = text { dv_iroh_address(endpoint.toULong(), timeoutMs, it) }

    override fun id(endpoint: Long): String? = text { dv_iroh_id(endpoint.toULong(), it) }

    override fun networkChange(endpoint: Long) {
        dv_iroh_network_change(endpoint.toULong())
    }

    override fun closeEndpoint(endpoint: Long): Unit = dv_iroh_close_endpoint(endpoint.toULong())

    override fun connect(endpoint: Long, address: String, timeoutMs: Long): Long = memScoped {
        val connection = alloc<ULongVar>()
        number(dv_iroh_connect(endpoint.toULong(), address, timeoutMs, connection.ptr), connection.value)
    }

    override fun accept(endpoint: Long, timeoutMs: Long): Long = memScoped {
        val connection = alloc<ULongVar>()
        number(dv_iroh_accept(endpoint.toULong(), timeoutMs, connection.ptr), connection.value)
    }

    override fun remoteId(connection: Long): String? = text { dv_iroh_remote_id(connection.toULong(), it) }

    override fun pathKind(connection: Long): Int = memScoped {
        val kind = alloc<IntVar>()
        val rtt = alloc<LongVar>()
        if (dv_iroh_path(connection.toULong(), kind.ptr, rtt.ptr) == DV_IROH_OK) kind.value else IrohBridge.PATH_NONE
    }

    override fun closeConnection(connection: Long): Unit = dv_iroh_close_connection(connection.toULong())

    override fun openStream(connection: Long): Long = memScoped {
        val stream = alloc<ULongVar>()
        number(dv_iroh_open_stream(connection.toULong(), stream.ptr), stream.value)
    }

    override fun acceptStream(connection: Long, timeoutMs: Long): Long = memScoped {
        val stream = alloc<ULongVar>()
        number(dv_iroh_accept_stream(connection.toULong(), timeoutMs, stream.ptr), stream.value)
    }

    override fun send(stream: Long, frame: ByteArray, timeoutMs: Long): Int {
        val code = if (frame.isEmpty()) {
            dv_iroh_send(stream.toULong(), null, 0u, timeoutMs)
        } else {
            frame.usePinned { bytes -> dv_iroh_send(stream.toULong(), bytes.addressOf(0).reinterpret(), frame.size.convert(), timeoutMs) }
        }
        return when (code) {
            DV_IROH_OK -> IrohBridge.SENT
            DV_IROH_TIMEOUT -> IrohBridge.SEND_TIMEOUT
            DV_IROH_CLOSED -> IrohBridge.SEND_CLOSED
            else -> IrohBridge.SEND_ERROR
        }
    }

    override fun receive(stream: Long, timeoutMs: Long, maxSize: Int): ByteArray? = memScoped {
        val bytes = alloc<DvIrohBytes>()
        if (dv_iroh_receive(stream.toULong(), timeoutMs, maxSize.coerceAtLeast(0).toULong(), bytes.ptr) != DV_IROH_OK) return@memScoped null
        val frame = bytes.data?.readBytes(bytes.length.toInt()) ?: ByteArray(0)
        dv_iroh_bytes_free(bytes.readValue())
        frame
    }

    override fun isEnded(stream: Long): Boolean = dv_iroh_is_ended(stream.toULong())

    override fun closeStream(stream: Long): Unit = dv_iroh_close_stream(stream.toULong())

    /** The number given with [code], or what [code] says went wrong. */
    private fun number(code: Int, value: ULong): Long = when (code) {
        DV_IROH_OK -> value.toLong()
        DV_IROH_TIMEOUT -> IrohBridge.TIMEOUT
        DV_IROH_CLOSED -> IrohBridge.CLOSED
        DV_IROH_ERROR -> IrohBridge.ERROR
        else -> IrohBridge.ERROR
    }

    /** The text a C function gives through [call] (freed here), `null` when it fails. */
    private inline fun text(call: MemScope.(CPointer<CPointerVar<ByteVar>>) -> Int): String? = memScoped {
        val text = alloc<CPointerVar<ByteVar>>()
        if (call(text.ptr) != DV_IROH_OK) return@memScoped null
        val value = text.value?.toKString()
        dv_iroh_string_free(text.value)
        value
    }
}

/** The static library of the bridge is linked with the module (cinterop). */
internal actual fun loadIrohBridge(): IrohBridge? = IrohCinterop
