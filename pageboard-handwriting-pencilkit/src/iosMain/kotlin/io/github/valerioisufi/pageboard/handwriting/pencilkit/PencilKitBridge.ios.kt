package io.github.valerioisufi.pageboard.handwriting.pencilkit

import io.github.valerioisufi.pageboard.handwriting.pencilkit.cinterop.pk_bridge_available
import io.github.valerioisufi.pageboard.handwriting.pencilkit.cinterop.pk_bridge_free
import io.github.valerioisufi.pageboard.handwriting.pencilkit.cinterop.pk_bridge_languages
import io.github.valerioisufi.pageboard.handwriting.pencilkit.cinterop.pk_bridge_recognize
import io.github.valerioisufi.pageboard.handwriting.pencilkit.cinterop.pk_bridge_version
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

// The PencilKit bridge linked as a static library (native/bridge/pencilkit_bridge.swift)

@OptIn(ExperimentalForeignApi::class)
internal actual fun pencilKitVersion(): Int = pk_bridge_version()

@OptIn(ExperimentalForeignApi::class)
internal actual fun pencilKitLanguages(): String = taken(pk_bridge_languages()).orEmpty()

@OptIn(ExperimentalForeignApi::class)
internal actual fun pencilKitAvailable(timeoutMs: Int): Boolean = pk_bridge_available(timeoutMs) == 1

@OptIn(ExperimentalForeignApi::class)
internal actual fun pencilKitRecognize(language: String, samples: PencilKitSamples, timeoutMs: Int): Pair<String, String>? {
    if (samples.sizes.isEmpty()) return null
    return memScoped {
        val text = alloc<CPointerVar<ByteVar>>()
        val search = alloc<CPointerVar<ByteVar>>()
        val read = samples.xy.usePinned { xy ->
            samples.times.usePinned { times ->
                samples.pressures.usePinned { pressures ->
                    samples.sizes.usePinned { sizes ->
                        pk_bridge_recognize(
                            language, xy.addressOf(0), times.addressOf(0), pressures.addressOf(0), sizes.addressOf(0),
                            samples.sizes.size, timeoutMs, text.ptr, search.ptr
                        )
                    }
                }
            }
        }
        val readText = taken(text.value)
        val readSearch = taken(search.value)
        if (read == 1 && readText != null) readText to readSearch.orEmpty() else null
    }
}

/** The string given by the bridge, freed. */
@OptIn(ExperimentalForeignApi::class)
private fun taken(string: CPointer<ByteVar>?): String? = string?.let { it.toKString().also { _ -> pk_bridge_free(string) } }
