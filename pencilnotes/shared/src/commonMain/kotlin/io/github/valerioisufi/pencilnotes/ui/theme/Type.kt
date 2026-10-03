package io.github.valerioisufi.pencilnotes.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.pencilnotes.generated.resources.Res
import io.github.valerioisufi.pencilnotes.generated.resources.cherry_bomb_one
import org.jetbrains.compose.resources.Font

// Set of Material typography styles to start with
val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    )
)

/** The Cherry Bomb One font of the PencilNotes title. */
@Composable
fun cherryBombOneFontFamily(): FontFamily {
    val font = Font(Res.font.cherry_bomb_one, FontWeight.W400, FontStyle.Normal)
    return remember(font) { FontFamily(font) }
}
