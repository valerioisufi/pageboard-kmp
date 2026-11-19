package io.github.valerioisufi.pencilnotes

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyboardShortcutGroup
import android.view.Menu
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import io.github.valerioisufi.pageboard.platform.DrawViewAndroid
import io.github.valerioisufi.pageboard.ui.command.CommandHost
import io.github.valerioisufi.pageboard.ui.command.keyboardShortcutGroups

class MainActivity : ComponentActivity() {
    /** The commands of the screen shown: their shortcuts in the list of the system (Meta + /). */
    private val commands = CommandHost()

    private var activeDocumentId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Context used by the database, the document resources and the TeX fonts
        DrawViewAndroid.init(applicationContext)
        alive++

        activeDocumentId = documentIdOf(intent)
        openInvitationOf(intent)

        setContent {
            val docId = activeDocumentId
            if (docId != null) {
                PencilNotesDocumentWindow(
                    documentId = docId,
                    onClose = { finish() },
                    commandHost = commands
                )
            } else {
                PencilNotesApp(
                    commandHost = commands,
                    onOpenDocumentInNewWindow = { id -> openDocumentInNewTask(id) }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        alive--
        // The last window of the application closed for good: no more work with the other devices
        if (isFinishing && alive == 0) PencilNotesServices.stopCollaboration()
    }

    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        data.addAll(commands.keyboardShortcutGroups())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        activeDocumentId = documentIdOf(intent)
        openInvitationOf(intent)
    }

    /** Opens the given note in a separate document task (split-screen / DeX multi-window). */
    private fun openDocumentInNewTask(documentId: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("drawview://document/$documentId"), this, MainActivity::class.java).apply {
            putExtra(EXTRA_DOCUMENT_ID, documentId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        }
        startActivity(intent)
    }

    /** Extracts a document ID from an intent with drawview://document/<id> or EXTRA_DOCUMENT_ID. */
    private fun documentIdOf(intent: Intent?): String? {
        if (intent == null) return null
        intent.getStringExtra(EXTRA_DOCUMENT_ID)?.let { return it }
        val data = intent.data ?: return null
        if (data.scheme == "drawview" && data.host == "document") {
            return data.pathSegments.firstOrNull() ?: data.lastPathSegment
        }
        return null
    }

    /** An invitation to a shared document opened on the device (a drawview://join link). */
    private fun openInvitationOf(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "drawview" && data.host == "join") {
            openInvitation(data.toString())
        }
    }

    companion object {
        /** The activities of PencilNotes created and not destroyed (a document may have a task of its own). */
        private var alive = 0

        private const val EXTRA_DOCUMENT_ID = "io.github.valerioisufi.pageboard.DOCUMENT_ID"
    }
}

