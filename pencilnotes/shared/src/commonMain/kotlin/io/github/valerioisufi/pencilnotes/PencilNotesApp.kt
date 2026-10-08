package io.github.valerioisufi.pencilnotes

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.data.DrawViewStore
import io.github.valerioisufi.drawview.explorer.DocumentLibrary
import io.github.valerioisufi.drawview.explorer.ui.AboutLicensesButton
import io.github.valerioisufi.drawview.export.ui.rememberDocumentExportUi
import io.github.valerioisufi.drawview.lecture.ui.LectureDialogs
import io.github.valerioisufi.drawview.lecture.ui.LectureRecordingIndicator
import io.github.valerioisufi.drawview.lecture.ui.editorExtension
import io.github.valerioisufi.drawview.lecture.ui.rememberLectureAssistant
import io.github.valerioisufi.drawview.sync.ui.CollaborationDialogs
import io.github.valerioisufi.drawview.sync.ui.InvitationLinks
import io.github.valerioisufi.drawview.sync.ui.JoinSharedDocumentButton
import io.github.valerioisufi.drawview.sync.ui.editorExtension
import io.github.valerioisufi.drawview.sync.ui.rememberCollaboration
import io.github.valerioisufi.drawview.ui.adaptive.AdaptiveRoot
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
        // Lecture recording, followed by Gemini or a local model: it goes on in the background
        val lectureAssistant = rememberLectureAssistant()
        // The documents shared with the devices of the local network, with their lectures
        val collaboration = rememberCollaboration(attachments = lectureAssistant.attachments)
        // A tablet uses the models of a computer of the local network (Ollama)
        LaunchedEffect(lectureAssistant, collaboration) { lectureAssistant.localModels.nearbyHosts = collaboration::nearbyHosts }
        // The documents exported as PDF or as the image of a page
        val export = rememberDocumentExportUi(appName = "PencilNotes")
        val editorExtension = remember(lectureAssistant, collaboration, export) {
            lectureAssistant.editorExtension() + collaboration.editorExtension() + export.editorExtension
        }
        // The lectures of the documents deleted go with them
        LaunchedEffect(lectureAssistant) {
            var known: Set<String>? = null
            DrawViewStore.default.documents.collect { documents ->
                val ids = documents.mapTo(mutableSetOf()) { it.id }
                known?.let { lectureAssistant.forgetDocuments(it - ids) }
                known = ids
            }
        }
        // The dialogs of the application adapt like the library: one root for all of them
        AdaptiveRoot(modifier = Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalAboutAppInfo provides aboutAppInfo) {
                CollaborationDialogs(collaboration)
                LectureDialogs(lectureAssistant)
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
                    actions = {
                        JoinSharedDocumentButton(collaboration)
                        AboutLicensesButton()
                    },
                    fullScreenEditor = true,
                    editorExtension = editorExtension,
                    documentActions = listOf(export.documentAction),
                    libraryOverlay = { openDocument ->
                        LectureRecordingIndicator(
                            assistant = lectureAssistant,
                            onOpenDocument = openDocument,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(16.dp)
                        )
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/** Opens the invitation [link] to a shared document (a `drawview://join` link opened on the device). */
fun openInvitation(link: String) = InvitationLinks.open(link)

/** The font of the PencilNotes title, listed by the About & Licenses dialog. */
private val CherryBombOneLicense = ThirdPartyLicense(
    name = "Cherry Bomb One",
    license = "SIL OFL 1.1",
    copyright = "Copyright © 2019 The Cherry Bomb Project Authors (https://github.com/satsuyako/CherryBomb)",
    purpose = "Typography of the PencilNotes title.",
    notice = "Cherry Bomb One is licensed under the SIL Open Font License, Version 1.1. This license is available with a FAQ at: https://openfontlicense.org"
)
