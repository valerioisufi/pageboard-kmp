package io.github.valerioisufi.drawview.latex

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import io.github.valerioisufi.drawview.canvas.render.tile.FormulaImage
import io.github.valerioisufi.drawview.canvas.render.tile.FormulaRenderer
import io.github.valerioisufi.drawview.canvas.render.tile.TextFace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Platform binding of the native MicroTeX engine.
 */
internal expect object LatexPlatformEngine {
    /** Initializes the native engine with the directory of the unpacked TeX resources. */
    fun init(resourcesDirectory: String): Boolean
    fun isNativeReady(): Boolean
    fun renderFormula(latex: String, fontSizePx: Float, colorArgb: Int, paddingPx: Float): LatexResult?

    /** The drawing of a sanitized formula (see [LatexEngine.drawing]), or `null`. */
    fun drawing(latex: String, fontSizePx: Float, colorArgb: Int): TexDrawing?
    fun generateErrorBitmap(latex: String, fontSizePx: Float): ImageBitmap
}

/**
 * Directory where the TeX resources (fonts and symbol tables) are unpacked on this platform.
 */
internal expect fun texResourcesDirectory(): String

/**
 * High-level engine for parsing, sanitizing, and rendering LaTeX mathematical formulas
 * using the native MicroTeX C++ engine (JNI on Android and on the desktop, cinterop on iOS).
 */
public object LatexEngine {

    private val initMutex = Mutex()
    private val _isReady = MutableStateFlow(false)

    /** Emits `true` once the engine is ready to render formulas. */
    public val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

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
        } catch (e: Throwable) {
            println("LatexEngine: initialization failed: ${e.message}")
            false
        }
        _isReady.value = ready
        ready
    }

    /**
     * Initializes the native engine with an already unpacked resources directory.
     */
    public fun init(resourcesDirectory: String): Boolean {
        val ready = LatexPlatformEngine.isNativeReady() || LatexPlatformEngine.init(resourcesDirectory)
        _isReady.value = ready
        return ready
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

    /**
     * Renders a sanitized LaTeX formula to a LatexResult containing ImageBitmap and baseline ratio.
     * Returns `null` when the formula is invalid or the engine is not initialized yet.
     */
    public fun renderFormula(
        latex: String,
        fontSizePx: Float,
        color: Color,
        paddingPx: Float = 0f
    ): LatexResult? = renderFormula(latex, fontSizePx, color.toArgb(), paddingPx)

    /**
     * Renders a sanitized LaTeX formula using an ARGB integer color.
     */
    public fun renderFormula(
        latex: String,
        fontSizePx: Float,
        colorArgb: Int,
        paddingPx: Float = 0f
    ): LatexResult? {
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
     * platform (characters of its fonts, lines, rectangles: see [TexDrawing]), e.g. to write it as
     * vectors; `null` when it is invalid or the engine is not initialized yet.
     */
    public fun drawing(latex: String, fontSizePx: Float, colorArgb: Int): TexDrawing? {
        val clean = sanitizeLatexString(latex)
        if (clean.isBlank() || !LatexPlatformEngine.isNativeReady()) return null
        return try {
            LatexPlatformEngine.drawing(clean, fontSizePx.coerceAtLeast(1f), colorArgb)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * The fonts of the text of the formulas (`\text{}`), TrueType files by style (0 regular,
     * [TexFont.BOLD], [TexFont.ITALIC], both): the same on every platform, instead of the fonts of
     * the system. Set before the first formula.
     */
    public fun setTextFonts(byStyle: Map<Int, ByteArray>): Unit = TexTextFonts.set(byStyle)

    /**
     * Generates an error warning badge ImageBitmap for invalid LaTeX syntax.
     */
    internal fun generateErrorBitmap(latex: String, fontSizePx: Float): ImageBitmap {
        return LatexPlatformEngine.generateErrorBitmap(latex, fontSizePx)
    }

    /**
     * The engine as the formula renderer of the editors, the thumbnails and the export (give it
     * to `DrawViewEngines`).
     */
    public val formulaRenderer: FormulaRenderer = object : FormulaRenderer {
        override val isReady: StateFlow<Boolean> get() = this@LatexEngine.isReady

        override suspend fun prepare(textFonts: Map<TextFace, ByteArray>) {
            if (textFonts.isNotEmpty()) {
                setTextFonts(textFonts.mapNotNull { (face, bytes) -> texStyle(face)?.let { it to bytes } }.toMap())
            }
            initialize()
        }

        override fun sanitize(latex: String): String = sanitizeLatexString(latex)

        override fun render(latex: String, fontSizePx: Float, colorArgb: Int): FormulaImage? =
            renderFormula(latex, fontSizePx, colorArgb, paddingPx = 0f)?.let { FormulaImage(it.bitmap, it.baselineRatio) }
    }

    /** The style of the text fonts of MicroTeX for [face] (`null` for the code and the symbols). */
    private fun texStyle(face: TextFace): Int? = when (face) {
        TextFace.REGULAR -> 0
        TextFace.BOLD -> TexFont.BOLD
        TextFace.ITALIC -> TexFont.ITALIC
        TextFace.BOLD_ITALIC -> TexFont.BOLD or TexFont.ITALIC
        TextFace.MONO, TextFace.MONO_BOLD, TextFace.SYMBOLS -> null
    }
}
