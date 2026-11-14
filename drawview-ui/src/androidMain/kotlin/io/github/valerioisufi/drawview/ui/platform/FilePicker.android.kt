package io.github.valerioisufi.drawview.ui.platform

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
public actual fun rememberFilePicker(type: PickerType, onPicked: (PickedFile) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnPicked by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val file = withContext(Dispatchers.IO) { readUri(context, uri) }
                if (file != null) currentOnPicked(file)
            }
        }
    }
    return {
        launcher.launch(
            when (type) {
                PickerType.IMAGE -> "image/*"
                PickerType.PDF -> "application/pdf"
                PickerType.TEXT -> "text/*"
                // An archive has an extension of its own, which the systems do not know
                PickerType.ARCHIVE -> "*/*"
            }
        )
    }
}

private fun readUri(context: Context, uri: Uri): PickedFile? = try {
    val resolver = context.contentResolver
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    var name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: uri.lastPathSegment ?: "file"
    if (!name.contains('.')) {
        val extension = resolver.getType(uri)?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        if (extension != null) name = "$name.$extension"
    }
    PickedFile(name, bytes)
} catch (e: Exception) {
    null
}
