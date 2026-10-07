package io.github.valerioisufi.drawview.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import io.github.valerioisufi.drawview.core.render.tile.TextFace
import io.github.valerioisufi.drawview.ui.generated.resources.Res
import io.github.valerioisufi.drawview.ui.generated.resources.noto_sans_bold
import io.github.valerioisufi.drawview.ui.generated.resources.noto_sans_bold_italic
import io.github.valerioisufi.drawview.ui.generated.resources.noto_sans_italic
import io.github.valerioisufi.drawview.ui.generated.resources.noto_sans_mono_bold
import io.github.valerioisufi.drawview.ui.generated.resources.noto_sans_mono_regular
import io.github.valerioisufi.drawview.ui.generated.resources.noto_sans_regular
import org.jetbrains.compose.resources.Font

/**
 * The fonts of the text of the notes, the same on every platform: the editor draws and edits the
 * texts with them, the export embeds them in the PDFs (see [NotesFontFiles]).
 *
 * @param text the text (Noto Sans).
 * @param monospace the code (Noto Sans Mono).
 */
@Immutable
class NotesFonts(val text: FontFamily, val monospace: FontFamily) {
    companion object {
        /** The fonts of the system: every platform draws the texts with its own. */
        val System = NotesFonts(FontFamily.Default, FontFamily.Monospace)
    }
}

/** The fonts of the notes, Noto Sans and Noto Sans Mono (shipped with DrawView). */
@Composable
fun rememberNotesFonts(): NotesFonts {
    val regular = Font(Res.font.noto_sans_regular, FontWeight.Normal, FontStyle.Normal)
    val italic = Font(Res.font.noto_sans_italic, FontWeight.Normal, FontStyle.Italic)
    val bold = Font(Res.font.noto_sans_bold, FontWeight.Bold, FontStyle.Normal)
    val boldItalic = Font(Res.font.noto_sans_bold_italic, FontWeight.Bold, FontStyle.Italic)
    val mono = Font(Res.font.noto_sans_mono_regular, FontWeight.Normal)
    val monoBold = Font(Res.font.noto_sans_mono_bold, FontWeight.Bold)
    return remember(regular, italic, bold, boldItalic, mono, monoBold) {
        NotesFonts(FontFamily(regular, italic, bold, boldItalic), FontFamily(mono, monoBold))
    }
}

/** The files of the fonts of the notes ([rememberNotesFonts]), to embed them in a PDF. */
object NotesFontFiles {
    /** The TrueType file of [face]. */
    suspend fun bytes(face: TextFace): ByteArray = Res.readBytes(
        when (face) {
            TextFace.REGULAR -> "font/noto_sans_regular.ttf"
            TextFace.ITALIC -> "font/noto_sans_italic.ttf"
            TextFace.BOLD -> "font/noto_sans_bold.ttf"
            TextFace.BOLD_ITALIC -> "font/noto_sans_bold_italic.ttf"
            TextFace.MONO -> "font/noto_sans_mono_regular.ttf"
            TextFace.MONO_BOLD -> "font/noto_sans_mono_bold.ttf"
        }
    )
}
