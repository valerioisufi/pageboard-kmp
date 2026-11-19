package io.github.valerioisufi.pageboard.ui.window

import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.util.Collections

/**
 * The title bar of the windows on macOS, set through AppKit: an empty toolbar in the unified
 * style makes the title bar as tall as the toolbars of macOS (52 points), with the traffic lights
 * centered in its height, as in the applications of the system. The toolbar shows nothing, and
 * the clicks out of the traffic lights still reach the content of the window.
 *
 * AppKit takes these changes on its main thread only: from another thread, the one of AWT too,
 * it throws an exception that ends the process, which no `catch` stops. So they are sent to the
 * main thread, and they only touch a window that is an `NSWindow` knowing the toolbar styles
 * (macOS 11).
 */
internal object MacWindowHelper {
    /** `NSWindowToolbarStyleUnified`. */
    private const val TOOLBAR_STYLE_UNIFIED = 3L

    /** `NSTitlebarSeparatorStyleNone`: no line under the title bar. */
    private const val TITLEBAR_SEPARATOR_NONE = 1L

    /**
     * Gives the window [windowHandle] (its `NSWindow`, as `ComposeWindow.windowHandle`) the title
     * bar of a unified toolbar, later, on the main thread. Nothing happens out of macOS, or when
     * the native libraries cannot be reached.
     */
    fun useUnifiedToolbar(windowHandle: Long) {
        if (!isMac || windowHandle == 0L) return
        try {
            ObjC.onMainThread {
                val window = Pointer(windowHandle)
                if (!ObjC.isKindOf(window, "NSWindow") || !ObjC.respondsTo(window, "setToolbarStyle:")) {
                    return@onMainThread
                }
                val toolbar = ObjC.send(ObjC.send(ObjC.cls("NSToolbar"), "alloc"), "initWithIdentifier:", ObjC.string("DrawViewWindow"))
                ObjC.sendVoid(window, "setToolbar:", toolbar)
                // The window keeps its toolbar
                ObjC.sendVoid(toolbar, "release")
                ObjC.sendVoid(window, "setToolbarStyle:", TOOLBAR_STYLE_UNIFIED)
                ObjC.sendVoid(window, "setTitlebarSeparatorStyle:", TITLEBAR_SEPARATOR_NONE)
            }
        } catch (_: Throwable) {
            // Without JNA or AppKit, the title bar of AWT stays
        }
    }

    private val isMac: Boolean = System.getProperty("os.name").orEmpty().lowercase().let { "mac" in it || "darwin" in it }
}

/**
 * The calls to the Objective-C runtime of [MacWindowHelper], through JNA: the messages sent by
 * `objc_msgSend`, and the code run on the main thread by `dispatch_async_f`.
 */
internal object ObjC {
    private val objc: NativeLibrary by lazy { NativeLibrary.getInstance("objc") }
    private val system: NativeLibrary by lazy { NativeLibrary.getInstance("System") }
    private val msgSend: Function by lazy { objc.getFunction("objc_msgSend") }

    /** The function `dispatch_async_f` runs on the main thread (a `dispatch_function_t`). */
    fun interface DispatchFunction : Callback {
        fun invoke(context: Pointer?)
    }

    /** The functions waiting for the main thread, kept from the garbage collector until they run. */
    private val pending: MutableSet<DispatchFunction> = Collections.synchronizedSet(HashSet())

    /**
     * Runs [block] on the main thread of AppKit, later. A [Throwable] of [block] is dropped: it
     * would reach the run loop of AppKit.
     */
    fun onMainThread(block: () -> Unit) {
        val function = object : DispatchFunction {
            override fun invoke(context: Pointer?) {
                try {
                    block()
                } catch (_: Throwable) {
                } finally {
                    pending.remove(this)
                }
            }
        }
        pending.add(function)
        val mainQueue = system.getGlobalVariableAddress("_dispatch_main_q")
        system.getFunction("dispatch_async_f").invokeVoid(arrayOf(mainQueue, null, function))
    }

    fun cls(name: String): Pointer? = objc.getFunction("objc_getClass").invokePointer(arrayOf(name))

    fun sel(name: String): Pointer? = objc.getFunction("sel_registerName").invokePointer(arrayOf(name))

    fun send(receiver: Pointer?, selector: String, vararg args: Any?): Pointer? =
        msgSend.invokePointer(arrayOf(receiver, sel(selector), *args))

    fun sendLong(receiver: Pointer?, selector: String, vararg args: Any?): Long =
        msgSend.invokeLong(arrayOf(receiver, sel(selector), *args))

    fun sendVoid(receiver: Pointer?, selector: String, vararg args: Any?) {
        msgSend.invokeVoid(arrayOf(receiver, sel(selector), *args))
    }

    /** A message answering a `BOOL`, a byte: the rest of the register is not part of it. */
    fun sendBoolean(receiver: Pointer?, selector: String, vararg args: Any?): Boolean =
        msgSend.invokeInt(arrayOf(receiver, sel(selector), *args)) and 0xFF != 0

    /** An `NSString` of [value], released with the autorelease pool of the main thread. */
    fun string(value: String): Pointer? = send(cls("NSString"), "stringWithUTF8String:", value)

    fun isKindOf(target: Pointer, className: String): Boolean = sendBoolean(target, "isKindOfClass:", cls(className))

    fun respondsTo(target: Pointer, selector: String): Boolean = sendBoolean(target, "respondsToSelector:", sel(selector))
}
