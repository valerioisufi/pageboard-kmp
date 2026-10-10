package io.github.valerioisufi.drawview.ui.render

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.valerioisufi.drawview.core.render.DrawViewEngines

/**
 * The engines drawing the PDF pages and the formulas, for what draws documents without an editor
 * at hand (the notes of an assistant, an export from the library): provided by the editor
 * ([io.github.valerioisufi.drawview.ui.screen.DrawScreen]) and by the library of the documents.
 */
val LocalDrawViewEngines = staticCompositionLocalOf { DrawViewEngines.None }
