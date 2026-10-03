package io.github.valerioisufi.pencilnotes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import io.github.valerioisufi.drawview.core.platform.DrawViewAndroid

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Context used by the database, the document resources and the TeX fonts
        DrawViewAndroid.init(applicationContext)

        setContent {
            PencilNotesApp()
        }
    }
}
