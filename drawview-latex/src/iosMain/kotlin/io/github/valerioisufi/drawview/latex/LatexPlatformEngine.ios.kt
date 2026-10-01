package io.github.valerioisufi.drawview.latex

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import io.github.valerioisufi.drawview.core.util.SyncLock
import io.github.valerioisufi.drawview.core.util.syncLock
import io.github.valerioisufi.drawview.latex.cinterop.TexCallbacks
import io.github.valerioisufi.drawview.latex.cinterop.TEX_ACT_DRAW_TEXT
import io.github.valerioisufi.drawview.latex.cinterop.TEX_ACT_SET_FONT
import io.github.valerioisufi.drawview.latex.cinterop.tex_init
import io.github.valerioisufi.drawview.latex.cinterop.tex_parse
import io.github.valerioisufi.drawview.latex.cinterop.tex_render_draw
import io.github.valerioisufi.drawview.latex.cinterop.tex_render_free
import io.github.valerioisufi.drawview.latex.cinterop.tex_render_get_baseline
import io.github.valerioisufi.drawview.latex.cinterop.tex_render_get_height
import io.github.valerioisufi.drawview.latex.cinterop.tex_render_get_width
import io.github.valerioisufi.drawview.latex.cinterop.tex_set_callbacks
import io.nano.tex.ActionRecorder
import io.nano.tex.Font
import io.nano.tex.Graphics2D
import io.nano.tex.TextLayout
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * Registry of the Kotlin objects referenced by the native engine through opaque handles.
 */
@OptIn(ExperimentalForeignApi::class)
private object TexObjects {
    val lock = SyncLock()
    private var nextHandle = 1L
    private val fonts = mutableMapOf<Long, Font>()
    private val recorders = mutableMapOf<Long, ActionRecorder>()

    fun registerFont(font: Font): Long = syncLock(lock) {
        val handle = nextHandle++
        fonts[handle] = font
        handle
    }

    fun font(handle: Long): Font? = syncLock(lock) { fonts[handle] }

    fun releaseFont(handle: Long) {
        syncLock(lock) { fonts.remove(handle) }
    }

    fun registerRecorder(recorder: ActionRecorder): Long = syncLock(lock) {
        val handle = nextHandle++
        recorders[handle] = recorder
        handle
    }

    fun recorder(handle: Long): ActionRecorder? = syncLock(lock) { recorders[handle] }

    fun releaseRecorder(handle: Long) {
        syncLock(lock) { recorders.remove(handle) }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun createFontFromFile(path: CPointer<ByteVar>?, size: Float): Long {
    val file = path?.toKString() ?: return 0L
    return TexObjects.registerFont(Font.create(file, size))
}

@OptIn(ExperimentalForeignApi::class)
private fun createFontFromName(name: CPointer<ByteVar>?, style: Int, size: Float): Long {
    val family = name?.toKString() ?: return 0L
    return TexObjects.registerFont(Font.create(family, style, size))
}

private fun deriveFont(handle: Long, style: Int): Long {
    val font = TexObjects.font(handle) ?: return 0L
    val derived = font.deriveFont(style)
    return if (derived === font) handle else TexObjects.registerFont(derived)
}

private fun releaseFont(handle: Long) {
    TexObjects.releaseFont(handle)
}

@OptIn(ExperimentalForeignApi::class)
private fun textBounds(text: CPointer<ByteVar>?, handle: Long, out: CPointer<FloatVar>?) {
    if (out == null) return
    val font = TexObjects.font(handle)
    val bounds = if (text != null && font != null) TextLayout.getBounds(text.toKString(), font) else null
    out[0] = bounds?.x ?: 0f
    out[1] = bounds?.y ?: 0f
    out[2] = bounds?.w ?: 0f
    out[3] = bounds?.h ?: 0f
}

@OptIn(ExperimentalForeignApi::class)
private fun record(recorder: Long, action: Int, fontHandle: Long, text: CPointer<ByteVar>?, args: CPointer<FloatVar>?, count: Int) {
    val target = TexObjects.recorder(recorder) ?: return
    val arg: Any? = when (action) {
        TEX_ACT_SET_FONT.toInt() -> TexObjects.font(fontHandle)
        TEX_ACT_DRAW_TEXT.toInt() -> text?.toKString()
        else -> null
    }
    val values = if (args != null && count > 0) FloatArray(count) { args[it] } else null
    target.record(action, arg, values)
}

@OptIn(ExperimentalForeignApi::class)
actual object LatexPlatformEngine {

    private val engineLock = SyncLock()
    private var initialized = false
    private var callbacksRegistered = false

    private fun registerCallbacks() {
        if (callbacksRegistered) return
        memScoped {
            val callbacks = alloc<TexCallbacks>()
            callbacks.create_font_from_file = staticCFunction(::createFontFromFile)
            callbacks.create_font_from_name = staticCFunction(::createFontFromName)
            callbacks.derive_font = staticCFunction(::deriveFont)
            callbacks.release_font = staticCFunction(::releaseFont)
            callbacks.text_bounds = staticCFunction(::textBounds)
            callbacks.record = staticCFunction(::record)
            // The callbacks are copied by the native side
            tex_set_callbacks(callbacks.ptr)
        }
        callbacksRegistered = true
    }

    actual fun init(resourcesDirectory: String): Boolean = syncLock(engineLock) {
        if (initialized) return@syncLock true
        registerCallbacks()
        initialized = tex_init(resourcesDirectory) == 1
        initialized
    }

    /** Whether a native engine exists on this platform. */
    actual fun isSupported(): Boolean = true

    actual fun isNativeReady(): Boolean = syncLock(engineLock) { initialized }

    actual fun renderFormula(latex: String, fontSizePx: Float, colorArgb: Int, paddingPx: Float): LatexResult? =
        syncLock(engineLock) {
            if (!initialized) return@syncLock null
            val render = tex_parse(latex, 0, fontSizePx.coerceAtLeast(1f), 0f, colorArgb)
            if (render == 0L) return@syncLock null
            try {
                val width = tex_render_get_width(render)
                val height = tex_render_get_height(render)
                val baseline = tex_render_get_baseline(render)
                val recorder = ActionRecorder()
                val recorderHandle = TexObjects.registerRecorder(recorder)
                try {
                    tex_render_draw(render, recorderHandle, 0, 0)
                } finally {
                    TexObjects.releaseRecorder(recorderHandle)
                }

                val pad = paddingPx.toInt()
                val w = (width + pad * 2).coerceAtLeast(1)
                val h = (height + pad * 2).coerceAtLeast(1)
                val bitmap = Bitmap().apply { allocN32Pixels(w, h) }
                val canvas = Canvas(bitmap)
                canvas.clear(0)
                recorder.setPosition(pad.toFloat(), pad.toFloat())
                recorder.play(Graphics2D(canvas))
                bitmap.setImmutable()
                LatexResult(bitmap.asComposeImageBitmap(), baseline.coerceIn(0f, 1f), w, h)
            } finally {
                tex_render_free(render)
            }
        }

    actual fun generateErrorBitmap(latex: String, fontSizePx: Float): ImageBitmap =
        SkiaLatexBadges.errorBadge(latex, fontSizePx)
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun texResourcesDirectory(): String {
    val base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String ?: ""
    val dir = "$base/drawview/tex"
    NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
    return dir
}
