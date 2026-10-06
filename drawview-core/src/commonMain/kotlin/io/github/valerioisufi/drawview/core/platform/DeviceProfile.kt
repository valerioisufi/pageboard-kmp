package io.github.valerioisufi.drawview.core.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How this device is shown to the devices it works with (GRASP Information Expert): the name the
 * user chose ([rename]), or the one of the device ([localDeviceName]). Kept in the data directory.
 */
object DeviceProfile {
    private val chosen = MutableStateFlow<String?>(null)
    private var loaded = false

    /** The name shown to the others. */
    val name: StateFlow<String> get() = state.also { ensureLoaded() }.asStateFlow()

    /** The name the user chose, `null` when it is the one of the device. */
    val chosenName: StateFlow<String?> get() = chosen.also { ensureLoaded() }.asStateFlow()

    private val state = MutableStateFlow("")

    /** Shows this device as [name] (blank: the name of the device again). */
    fun rename(name: String?) {
        ensureLoaded()
        val value = name?.trim()?.take(MAX_LENGTH)?.takeIf { it.isNotEmpty() }
        if (value == null) PlatformFiles.delete(path) else PlatformFiles.writeBytes(path, value.encodeToByteArray())
        chosen.value = value
        state.value = value ?: localDeviceName()
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        chosen.value = PlatformFiles.readBytes(path)?.decodeToString()?.trim()?.takeIf { it.isNotEmpty() }
        state.value = chosen.value ?: localDeviceName()
    }

    private val path: String get() = dataDirectory() + "/device-name.txt"

    const val MAX_LENGTH = 40
}

/** The directory of the data of the application (the documents' resources are in it). */
fun dataDirectory(): String = PlatformFiles.resourcesDirectory().trimEnd('/').substringBeforeLast('/')
