package io.github.valerioisufi.drawview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.valerioisufi.drawview.platform.DrawViewAndroid

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Context used by the database, the document resources and the TeX fonts
        DrawViewAndroid.init(applicationContext)

        setContent {
            App()
        }
    }
}
