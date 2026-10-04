package io.github.valerioisufi.pencilnotes

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.explorer.DocumentLibrary
import io.github.valerioisufi.drawview.lecture.ui.rememberLectureEditorExtension
import io.github.valerioisufi.drawview.ui.components.AboutAppInfo
import io.github.valerioisufi.drawview.ui.components.LocalAboutAppInfo
import io.github.valerioisufi.drawview.ui.components.ThirdPartyLicense
import io.github.valerioisufi.pencilnotes.generated.resources.Res
import io.github.valerioisufi.pencilnotes.generated.resources.about_description
import io.github.valerioisufi.pencilnotes.generated.resources.about_version
import io.github.valerioisufi.pencilnotes.generated.resources.app_name
import io.github.valerioisufi.pencilnotes.ui.theme.PencilNotesTheme
import io.github.valerioisufi.pencilnotes.ui.theme.cherryBombOneFontFamily
import org.jetbrains.compose.resources.stringResource

/**
 * PencilNotes: the DrawView document library with the PencilNotes identity.
 */
@Composable
fun PencilNotesApp() {
    PencilNotesTheme {
        val aboutAppInfo = AboutAppInfo(
            name = stringResource(Res.string.app_name),
            version = stringResource(Res.string.about_version),
            description = stringResource(Res.string.about_description),
            additionalLicenses = listOf(CherryBombOneLicense)
        )
        CompositionLocalProvider(LocalAboutAppInfo provides aboutAppInfo) {
            DocumentLibrary(
                title = {
                    Text(
                        text = stringResource(Res.string.app_name),
                        modifier = Modifier.padding(start = 16.dp, end = 24.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = cherryBombOneFontFamily()
                    )
                },
                fullScreenEditor = true,
                // Lecture recording followed by Gemini (desktop)
                editorExtension = rememberLectureEditorExtension(),
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** The font of the PencilNotes title, listed by the About & Licenses dialog. */
private val CherryBombOneLicense = ThirdPartyLicense(
    name = "Cherry Bomb One",
    license = "SIL OFL 1.1",
    copyright = "Copyright © 2019 The Cherry Bomb Project Authors (https://github.com/satsuyako/CherryBomb)",
    purpose = "Typography of the PencilNotes title.",
    notice = "Cherry Bomb One is licensed under the SIL Open Font License, Version 1.1. This license is available with a FAQ at: https://openfontlicense.org"
)
