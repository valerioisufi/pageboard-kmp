package io.github.valerioisufi.drawview.consumer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.github.valerioisufi.drawview.canvas.DrawCanvas
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.model.DrawDocument

/** The canvas of a document in memory, drawn with the pen. */
class CanvasActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val scope = rememberCoroutineScope()
            val controller = remember { DrawViewController(DrawDocument(), scope) }
            DrawCanvas(controller)
        }
    }
}
