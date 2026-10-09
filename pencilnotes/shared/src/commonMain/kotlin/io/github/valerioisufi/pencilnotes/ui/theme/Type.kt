package io.github.valerioisufi.pencilnotes.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import io.github.valerioisufi.pencilnotes.generated.resources.Res
import io.github.valerioisufi.pencilnotes.generated.resources.cherry_bomb_one
import org.jetbrains.compose.resources.Font

/** The Cherry Bomb One font of the PencilNotes wordmark. */
@Composable
fun cherryBombOneFontFamily(): FontFamily {
    val font = Font(Res.font.cherry_bomb_one, FontWeight.W400, FontStyle.Normal)
    return remember(font) { FontFamily(font) }
}
