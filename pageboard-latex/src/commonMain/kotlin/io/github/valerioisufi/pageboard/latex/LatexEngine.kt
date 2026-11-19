package io.github.valerioisufi.pageboard.latex

import androidx.compose.ui.graphics.ImageBitmap
import io.github.valerioisufi.pageboard.canvas.render.tile.FormulaImage
import io.github.valerioisufi.pageboard.canvas.render.tile.FormulaRenderer
import io.github.valerioisufi.pageboard.canvas.render.tile.TextFace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.valerioisufi.pageboard.canvas.render.tile.FormulaDrawing
import io.github.valerioisufi.pageboard.canvas.render.tile.FormulaFont

/**
 * Platform binding of the native MicroTeX engine.
 */
internal expect object LatexPlatformEngine {
    /** Initializes the native engine with the directory of the unpacked TeX resources. */
    fun init(resourcesDirectory: String): Boolean
    fun isNativeReady(): Boolean
    fun renderFormula(latex: String, fontSizePx: Float, colorArgb: Int, paddingPx: Float): LatexResult?

    /** The drawing of a sanitized formula (see [LatexEngine.drawing]), or `null`. */
    fun drawing(latex: String, fontSizePx: Float, colorArgb: Int): FormulaDrawing?
    fun generateErrorBitmap(latex: String, fontSizePx: Float): ImageBitmap
}

/**
 * Directory where the TeX resources (fonts and symbol tables) are unpacked on this platform.
 */
internal expect fun texResourcesDirectory(): String

/**
 * The formula renderer of MicroTeX (JNI on Android and on the desktop, cinterop on iOS): it parses,
 * sanitizes and renders the LaTeX formulas of the texts, for the editors, the thumbnails and the
 * export. The application creates one and gives it to `DrawViewEngines` (`formulas`).
 *
 * MicroTeX is one native engine per process, loaded and initialized once: the instances share it,
 * and the fonts of the text of the formulas are those of the last [prepare].
 */
public class LatexEngine : FormulaRenderer {

    private val initMutex = Mutex()
    private val _isReady = MutableStateFlow(false)

    /** Emits `true` once the engine is ready to render formulas. */
    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    /**
     * Unpacks the TeX resources (once per version) and initializes the native engine.
     * Safe to call several times and from any thread; heavy work: call it off the UI thread.
     *
     * @return `true` when the engine is ready.
     */
    public suspend fun initialize(): Boolean = initMutex.withLock {
        if (LatexPlatformEngine.isNativeReady()) {
            _isReady.value = true
            return@withLock true
        }
        val ready = try {
            val directory = texResourcesDirectory()
            TexResources.ensureUnpacked(directory) && LatexPlatformEngine.init(directory)
        } catch (e: CancellationException) {
            // Not a failure of the engine: initialized by the next call
            throw e
        } catch (e: Throwable) {
            println("LatexEngine: initialization failed: ${e.message}")
            false
        }
        _isReady.value = ready
        ready
    }

    /** Sets the fonts of the text of the formulas ([textFonts], when given), then [initialize]s the engine. */
    override suspend fun prepare(textFonts: Map<TextFace, ByteArray>) {
        if (textFonts.isNotEmpty()) {
            TexTextFonts.set(textFonts.mapNotNull { (face, bytes) -> texStyle(face)?.let { it to bytes } }.toMap())
        }
        initialize()
    }

    override fun sanitize(latex: String): String = sanitizeLatexString(latex)

    override fun render(latex: String, fontSizePx: Float, colorArgb: Int): FormulaImage? =
        renderFormula(latex, fontSizePx, colorArgb)?.let { FormulaImage(it.bitmap, it.baselineRatio) }

    /**
     * Renders the formula [latex] (sanitized here) at [fontSizePx] in [colorArgb], with [paddingPx]
     * around it; `null` when it is invalid or the engine is not initialized yet.
     */
    internal fun renderFormula(latex: String, fontSizePx: Float, colorArgb: Int, paddingPx: Float = 0f): LatexResult? {
        val clean = sanitizeLatexString(latex)
        if (clean.isBlank() || !LatexPlatformEngine.isNativeReady()) return null
        return try {
            LatexPlatformEngine.renderFormula(clean, fontSizePx, colorArgb, paddingPx)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * The formula [latex] as MicroTeX draws it at [fontSizePx] in [colorArgb], the same on every
     * platform (characters of its fonts, lines, rectangles: see [FormulaDrawing]), e.g. to write it
     * as vectors; `null` when it is invalid or the engine is not initialized yet.
     */
    override fun drawing(latex: String, fontSizePx: Float, colorArgb: Int): FormulaDrawing? {
        val clean = sanitizeLatexString(latex)
        if (clean.isBlank() || !LatexPlatformEngine.isNativeReady()) return null
        return try {
            LatexPlatformEngine.drawing(clean, fontSizePx.coerceAtLeast(1f), colorArgb)
        } catch (_: Throwable) {
            null
        }
    }

    /** An error badge for the invalid formula [latex]. */
    internal fun generateErrorBitmap(latex: String, fontSizePx: Float): ImageBitmap =
        LatexPlatformEngine.generateErrorBitmap(latex, fontSizePx)

    /** The style of the text fonts of MicroTeX for [face] (`null` for the code and the symbols). */
    private fun texStyle(face: TextFace): Int? = when (face) {
        TextFace.REGULAR -> 0
        TextFace.BOLD -> FormulaFont.BOLD
        TextFace.ITALIC -> FormulaFont.ITALIC
        TextFace.BOLD_ITALIC -> FormulaFont.BOLD or FormulaFont.ITALIC
        TextFace.MONO, TextFace.MONO_BOLD, TextFace.SYMBOLS -> null
    }
}

/**
 * Sanitizes raw LaTeX input by stripping delimiter markers ($$, $, \[, \]),
 * balancing unclosed braces during real-time typing, and prepending empty
 * bases to lone exponents/subscripts.
 */
internal fun sanitizeLatexString(raw: String): String {
    var s = raw.trim()
    if (s.startsWith("$$") && s.endsWith("$$") && s.length >= 4) {
        s = s.substring(2, s.length - 2).trim()
    } else if (s.startsWith("$") && s.endsWith("$") && s.length >= 2) {
        s = s.substring(1, s.length - 1).trim()
    } else if (s.startsWith("\\[") && s.endsWith("\\]") && s.length >= 4) {
        s = s.substring(2, s.length - 2).trim()
    } else if (s.startsWith("\\(") && s.endsWith("\\)") && s.length >= 4) {
        s = s.substring(2, s.length - 2).trim()
    }

    if (s.isBlank()) return ""

    if (s.startsWith("^") || s.startsWith("_")) {
        s = "{}$s"
    }

    if (s.endsWith("\\") && !s.endsWith("\\\\")) {
        s = s.dropLast(1)
    }

    var openBraces = 0
    var isEscaped = false
    for (ch in s) {
        if (isEscaped) {
            isEscaped = false
            continue
        }
        if (ch == '\\') {
            isEscaped = true
        } else if (ch == '{') {
            openBraces++
        } else if (ch == '}') {
            if (openBraces > 0) openBraces--
        }
    }
    if (openBraces > 0) {
        s += "}".repeat(openBraces)
    }

    return s
}
