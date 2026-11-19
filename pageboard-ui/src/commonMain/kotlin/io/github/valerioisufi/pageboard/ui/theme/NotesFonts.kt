package io.github.valerioisufi.pageboard.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import io.github.valerioisufi.pageboard.canvas.render.tile.TextFace
import io.github.valerioisufi.pageboard.ui.generated.resources.Res
import io.github.valerioisufi.pageboard.ui.generated.resources.notes_symbols
import io.github.valerioisufi.pageboard.ui.generated.resources.noto_sans_bold
import io.github.valerioisufi.pageboard.ui.generated.resources.noto_sans_bold_italic
import io.github.valerioisufi.pageboard.ui.generated.resources.noto_sans_italic
import io.github.valerioisufi.pageboard.ui.generated.resources.noto_sans_mono_bold
import io.github.valerioisufi.pageboard.ui.generated.resources.noto_sans_mono_regular
import io.github.valerioisufi.pageboard.ui.generated.resources.noto_sans_regular
import io.github.valerioisufi.pageboard.ui.generated.resources.strut_above
import io.github.valerioisufi.pageboard.ui.generated.resources.strut_below
import org.jetbrains.compose.resources.Font

/**
 * The fonts of the text of the notes, the same on every platform: the editor draws and edits the
 * texts with them, the export embeds them in the PDFs (see [NotesFontFiles]).
 *
 * @param text the text (Noto Sans).
 * @param monospace the code (Noto Sans Mono).
 * @param strutAbove a font whose lines have all their room above the baseline, and [strutBelow]
 *   all below: the room of the formulas and of the blocks of code in the lines, exact with them
 *   (approximated with [text] without them).
 * @param symbols the arrows and the other symbols [text] does not have (see `TextSymbols`); `null`:
 *   from the fonts of each system.
 */
@Immutable
public class NotesFonts(
    public val text: FontFamily,
    public val monospace: FontFamily,
    public val strutAbove: FontFamily? = null,
    public val strutBelow: FontFamily? = null,
    public val symbols: FontFamily? = null
) {
    public companion object {
        /** The fonts of the system: every platform draws the texts with its own. */
        public val System: NotesFonts = NotesFonts(FontFamily.Default, FontFamily.Monospace)
    }
}

/** The fonts of the notes, Noto Sans and Noto Sans Mono, and the strut fonts (shipped with Pageboard). */
@Composable
public fun rememberNotesFonts(): NotesFonts {
    val regular = Font(Res.font.noto_sans_regular, FontWeight.Normal, FontStyle.Normal)
    val italic = Font(Res.font.noto_sans_italic, FontWeight.Normal, FontStyle.Italic)
    val bold = Font(Res.font.noto_sans_bold, FontWeight.Bold, FontStyle.Normal)
    val boldItalic = Font(Res.font.noto_sans_bold_italic, FontWeight.Bold, FontStyle.Italic)
    val mono = Font(Res.font.noto_sans_mono_regular, FontWeight.Normal)
    val monoBold = Font(Res.font.noto_sans_mono_bold, FontWeight.Bold)
    val above = Font(Res.font.strut_above)
    val below = Font(Res.font.strut_below)
    val symbols = Font(Res.font.notes_symbols)
    return remember(regular, italic, bold, boldItalic, mono, monoBold, above, below, symbols) {
        NotesFonts(FontFamily(regular, italic, bold, boldItalic), FontFamily(mono, monoBold), FontFamily(above), FontFamily(below), FontFamily(symbols))
    }
}

/** The files of the fonts of the notes ([rememberNotesFonts]), to embed them in a PDF. */
public object NotesFontFiles {
    /** The TrueType file of [face]. */
    public suspend fun bytes(face: TextFace): ByteArray = Res.readBytes(
        when (face) {
            TextFace.REGULAR -> "font/noto_sans_regular.ttf"
            TextFace.ITALIC -> "font/noto_sans_italic.ttf"
            TextFace.BOLD -> "font/noto_sans_bold.ttf"
            TextFace.BOLD_ITALIC -> "font/noto_sans_bold_italic.ttf"
            TextFace.MONO -> "font/noto_sans_mono_regular.ttf"
            TextFace.MONO_BOLD -> "font/noto_sans_mono_bold.ttf"
            TextFace.SYMBOLS -> "font/notes_symbols.ttf"
        }
    )
}
