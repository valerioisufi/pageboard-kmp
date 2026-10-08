package io.github.valerioisufi.pencilnotes

import android.view.KeyboardShortcutGroup
import android.view.Menu
import io.github.valerioisufi.drawview.ui.command.CommandHost
import io.github.valerioisufi.drawview.ui.command.keyboardShortcutGroups
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import io.github.valerioisufi.drawview.core.platform.DrawViewAndroid

class MainActivity : ComponentActivity() {
    /** The commands of the screen shown: their shortcuts in the list of the system (Meta + /). */
    private val commands = CommandHost()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Context used by the database, the document resources and the TeX fonts
        DrawViewAndroid.init(applicationContext)

        openInvitationOf(intent)

        setContent {
            PencilNotesApp(commandHost = commands)
        }
    }

    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        data.addAll(commands.keyboardShortcutGroups())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openInvitationOf(intent)
    }

    /** An invitation to a shared document opened on the device (a drawview://join link). */
    private fun openInvitationOf(intent: Intent?) {
        intent?.data?.toString()?.let(::openInvitation)
    }
}
