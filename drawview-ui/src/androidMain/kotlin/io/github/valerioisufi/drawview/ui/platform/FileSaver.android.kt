package io.github.valerioisufi.drawview.ui.platform

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The documents of the system: the file is written in the cache, then copied where the user chose. */
@Composable
actual fun rememberFileSaver(title: String, mimeType: String, onTarget: (FileTarget) -> Unit): (suggestedName: String) -> Unit {
    val context = LocalContext.current
    val target by rememberUpdatedState(onTarget)
    // The name asked, for the file written in the cache
    val suggested = remember { arrayOf("file") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mimeType)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val directory = File(context.cacheDir, "export").apply { mkdirs() }
        target(FileTarget(File(directory, suggested[0]).absolutePath) { written ->
            val file = File(written)
            try {
                withContext(Dispatchers.IO) {
                    val output = checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "cannot write the file chosen" }
                    output.use { file.inputStream().use { input -> input.copyTo(it) } }
                }
            } finally {
                file.delete()
            }
        })
    }
    return { name ->
        suggested[0] = name.replace('/', '-')
        launcher.launch(name)
    }
}
