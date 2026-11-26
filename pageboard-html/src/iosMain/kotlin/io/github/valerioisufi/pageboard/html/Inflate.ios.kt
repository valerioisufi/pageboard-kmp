package io.github.valerioisufi.pageboard.html

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.zlib.ZLIB_VERSION
import platform.zlib.Z_FINISH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream

@OptIn(ExperimentalForeignApi::class)
internal actual fun inflateRaw(input: ByteArray, size: Int): ByteArray? {
    if (input.isEmpty()) return null
    // A byte more than it said it holds, to see it holding more
    val out = ByteArray(size + 1)
    val inflated = memScoped {
        val stream = alloc<z_stream>()
        input.usePinned { from ->
            out.usePinned { to ->
                stream.next_in = from.addressOf(0).reinterpret()
                stream.avail_in = input.size.convert()
                stream.next_out = to.addressOf(0).reinterpret()
                stream.avail_out = out.size.convert()
                // Raw deflate: no header of zlib
                if (inflateInit2_(stream.ptr, -15, ZLIB_VERSION, sizeOf<z_stream>().convert()) != Z_OK) return null
                try {
                    inflate(stream.ptr, Z_FINISH) == Z_STREAM_END && stream.total_out.toLong() == size.toLong()
                } finally {
                    inflateEnd(stream.ptr)
                }
            }
        }
    }
    return if (inflated) out.copyOf(size) else null
}
