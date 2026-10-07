package io.github.valerioisufi.drawview.lecture.platform

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The documents of the system (Storage Access Framework): the file is copied into the cache to be read. */
@Composable
internal actual fun rememberFileOpener(title: String, extensions: Set<String>, onChosen: (ChosenFile) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chosen by rememberUpdatedState(onChosen)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            withContext(Dispatchers.IO) { copyToCache(context, uri) }?.let(chosen)
        }
    }
    return { launcher.launch(arrayOf("audio/*", "video/mp4")) }
}

private fun copyToCache(context: Context, uri: Uri): ChosenFile? = try {
    val resolver = context.contentResolver
    var name = "audio"
    var lastModified: Long? = null
    resolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { cursor.getString(it) }?.let { name = it }
            cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED).takeIf { it >= 0 && !cursor.isNull(it) }
                ?.let { lastModified = cursor.getLong(it).takeIf { time -> time > 0 } }
        }
    }
    val directory = File(context.cacheDir, "import").apply { mkdirs() }
    val file = File(directory, name.replace('/', '-'))
    resolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: return null
    ChosenFile(file.absolutePath, name, lastModified, temporary = true)
} catch (e: Exception) {
    println("DrawView: cannot read the file chosen: ${e.message}")
    null
}
