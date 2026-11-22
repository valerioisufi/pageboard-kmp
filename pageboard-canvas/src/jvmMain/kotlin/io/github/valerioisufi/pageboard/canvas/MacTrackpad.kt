package io.github.valerioisufi.pageboard.canvas

import io.github.valerioisufi.pageboard.platform.JvmNativeLibraries
import io.github.valerioisufi.pageboard.platform.PageboardLog
import java.awt.EventQueue

/**
 * Gestures of a macOS trackpad that AWT does not deliver to Java: the phases of the two-finger
 * scroll (fingers put down or lifted, inertia of the system), which the mouse wheel events do not
 * carry, and the pinch. An event monitor of the application (`native/bridge/trackpad_bridge_mac.m`)
 * reports them to the listeners on the event dispatch thread, the phases of the scroll just before
 * the scroll events of the same gesture.
 */
internal object MacTrackpad {

    // NSEventPhase
    private const val BEGAN = 0x1
    private const val ENDED = 0x8
    private const val CANCELLED = 0x10
    private const val MAY_BEGIN = 0x20

    val isMacOS: Boolean = System.getProperty("os.name").orEmpty().lowercase().let {
        it.contains("mac") || it.contains("darwin")
    }

    private val scrollPhaseListeners = mutableListOf<(TrackpadScrollPhase, Long) -> Unit>()
    private val pinchListeners = mutableListOf<(TrackpadPinchPhase, Float) -> Unit>()

    /** Whether the gestures are reported: macOS, with `libtrackpad_bridge` loaded. */
    val isAvailable: Boolean by lazy {
        isMacOS && JvmNativeLibraries.load(MacTrackpad::class.java, "trackpad_bridge") &&
            try {
                nativeInstall()
                true
            } catch (e: LinkageError) {
                PageboardLog.warn("The trackpad gestures are not available", e)
                false
            }
    }

    /**
     * Calls [listener] with every phase of the scroll and its time (milliseconds, like the
     * `uptimeMillis` of the pointer events of the desktop).
     *
     * @return the function removing the listener.
     */
    fun addScrollPhaseListener(listener: (TrackpadScrollPhase, Long) -> Unit): () -> Unit {
        scrollPhaseListeners += listener
        return { scrollPhaseListeners -= listener }
    }

    /**
     * Calls [listener] with every step of a pinch and its scale factor (e.g. `1.05` for 5% larger).
     *
     * @return the function removing the listener.
     */
    fun addPinchListener(listener: (TrackpadPinchPhase, Float) -> Unit): () -> Unit {
        pinchListeners += listener
        return { pinchListeners -= listener }
    }

    fun dispatchScrollPhase(phase: TrackpadScrollPhase, timeMillis: Long) {
        for (listener in scrollPhaseListeners.toList()) listener(phase, timeMillis)
    }

    fun dispatchPinch(phase: TrackpadPinchPhase, factor: Float) {
        for (listener in pinchListeners.toList()) listener(phase, factor)
    }

    @JvmStatic
    private external fun nativeInstall()

    /** Called by the event monitor, on the AppKit thread, with the phases of a scroll `NSEvent`. */
    @JvmStatic
    fun onScrollPhase(phase: Int, momentumPhase: Int) {
        val scrollPhase = when {
            momentumPhase and BEGAN != 0 -> TrackpadScrollPhase.MOMENTUM_START
            momentumPhase and (ENDED or CANCELLED) != 0 -> TrackpadScrollPhase.MOMENTUM_END
            phase and (MAY_BEGIN or BEGAN) != 0 -> TrackpadScrollPhase.FINGERS_DOWN
            phase and (ENDED or CANCELLED) != 0 -> TrackpadScrollPhase.FINGERS_UP
            phase == 0 && momentumPhase == 0 -> TrackpadScrollPhase.WHEEL
            else -> return
        }
        val timeMillis = System.currentTimeMillis()
        EventQueue.invokeLater { dispatchScrollPhase(scrollPhase, timeMillis) }
    }

    /** Called by the event monitor, on the AppKit thread, with a magnify `NSEvent`. */
    @JvmStatic
    fun onMagnify(phase: Int, magnification: Double) {
        val factor = 1f + magnification.toFloat().coerceAtLeast(-0.5f)
        EventQueue.invokeLater {
            when {
                phase and BEGAN != 0 -> dispatchPinch(TrackpadPinchPhase.START, factor)
                phase and (ENDED or CANCELLED) != 0 -> dispatchPinch(TrackpadPinchPhase.END, factor)
                phase != 0 -> dispatchPinch(TrackpadPinchPhase.CHANGE, factor)
                // Without phases: a whole pinch at once
                else -> {
                    dispatchPinch(TrackpadPinchPhase.START, 1f)
                    dispatchPinch(TrackpadPinchPhase.CHANGE, factor)
                    dispatchPinch(TrackpadPinchPhase.END, 1f)
                }
            }
        }
    }
}
