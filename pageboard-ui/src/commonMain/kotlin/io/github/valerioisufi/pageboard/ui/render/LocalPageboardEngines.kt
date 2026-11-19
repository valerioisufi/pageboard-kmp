package io.github.valerioisufi.pageboard.ui.render

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.valerioisufi.pageboard.canvas.render.PageboardEngines

/**
 * The engines drawing the PDF pages and the formulas, for what draws documents without an editor
 * at hand (the notes of an assistant, an export from the library): provided by the editor
 * ([io.github.valerioisufi.pageboard.ui.screen.DocumentScreen]) and by the library of the documents.
 */
public val LocalPageboardEngines: ProvidableCompositionLocal<PageboardEngines> = staticCompositionLocalOf { PageboardEngines.None }
