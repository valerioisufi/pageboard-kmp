package io.github.valerioisufi.pencilnotes.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import io.github.valerioisufi.pageboard.ui.theme.PageboardTheme

/**
 * The theme of PencilNotes: the design of Pageboard (its palette is the one of PencilNotes), the
 * dynamic colors of the system on Android 12+; the brand in Cherry Bomb One ([cherryBombOneFontFamily]).
 */
@Composable
fun PencilNotesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    PageboardTheme(darkTheme = darkTheme, dynamicColor = dynamicColor, content = content)
}
