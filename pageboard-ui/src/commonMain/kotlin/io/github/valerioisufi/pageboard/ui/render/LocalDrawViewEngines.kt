package io.github.valerioisufi.pageboard.ui.render

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.valerioisufi.pageboard.canvas.render.DrawViewEngines

/**
 * The engines drawing the PDF pages and the formulas, for what draws documents without an editor
 * at hand (the notes of an assistant, an export from the library): provided by the editor
 * ([io.github.valerioisufi.pageboard.ui.screen.DrawScreen]) and by the library of the documents.
 */
public val LocalDrawViewEngines: ProvidableCompositionLocal<DrawViewEngines> = staticCompositionLocalOf { DrawViewEngines.None }
