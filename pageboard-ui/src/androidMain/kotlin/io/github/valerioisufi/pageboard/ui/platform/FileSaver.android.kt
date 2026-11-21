package io.github.valerioisufi.pageboard.ui.platform

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The documents of the system: the file is written in the cache, then copied where the user chose. */
@Composable
public actual fun rememberFileSaver(title: String, mimeType: String, onTarget: (FileTarget) -> Unit): (suggestedName: String) -> Unit {
    val context = LocalContext.current
    val target by rememberUpdatedState(onTarget)
    // The name asked, for the file written in the cache
    val suggested = remember { arrayOf("file") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mimeType)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val directory = File(File(context.cacheDir, "export"), UUID.randomUUID().toString()).apply { mkdirs() }
        target(FileTarget(File(directory, suggested[0]).absolutePath) { written ->
            val file = File(written)
            try {
                withContext(Dispatchers.IO) {
                    val output = checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "cannot write the file chosen" }
                    output.use { file.inputStream().use { input -> input.copyTo(it) } }
                }
            } finally {
                file.delete()
                directory.deleteRecursively()
            }
        })
    }
    return { name ->
        suggested[0] = name.replace('/', '-')
        launcher.launch(name)
    }
}

/**
 * The share sheet of the system: the file is written in a unique subdirectory of the cache (old
 * directories are pruned after 24 hours) and given to the application chosen through
 * [PageboardFileProvider].
 */
@Composable
public actual fun rememberFileSharer(title: String, mimeType: String, onTarget: (FileTarget) -> Unit): ((suggestedName: String) -> Unit)? {
    val context = LocalContext.current
    val target by rememberUpdatedState(onTarget)
    return remember(context, title, mimeType) {
        { name ->
            val sharedRoot = File(context.cacheDir, SHARED_DIRECTORY).apply { mkdirs() }
            val cutoff = System.currentTimeMillis() - SHARED_RETENTION_MS
            sharedRoot.listFiles()?.forEach { child ->
                if (child.lastModified() < cutoff) child.deleteRecursively()
            }
            val directory = File(sharedRoot, UUID.randomUUID().toString()).apply { mkdirs() }
            target(FileTarget(File(directory, name.replace('/', '-')).absolutePath) { written ->
                withContext(Dispatchers.Main) { share(context, File(written), title, mimeType) }
            })
        }
    }
}

private fun share(context: Context, file: File, title: String, mimeType: String) {
    val uri = FileProvider.getUriForFile(context, PageboardFileProvider.authority(context), file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(mimeType)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val chooser = Intent.createChooser(send, title)
    if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}

public actual fun fileReveal(): FileReveal? = null

/** The directory of the cache holding the files shared (see `res/xml/pageboard_shared_files.xml`). */
private const val SHARED_DIRECTORY = "pageboard-shared"

/** How long shared files stay in the cache before being pruned. */
private const val SHARED_RETENTION_MS = 86_400_000L
