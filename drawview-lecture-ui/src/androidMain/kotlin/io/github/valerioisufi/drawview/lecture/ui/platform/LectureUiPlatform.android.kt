package io.github.valerioisufi.drawview.lecture.ui.platform

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred

/** The microphone, and the notifications (the one of the recording going on in the background). */
@Composable
internal actual fun rememberMicrophoneAccess(): suspend () -> Boolean {
    val context = LocalContext.current
    val answer = remember { arrayOfNulls<CompletableDeferred<Boolean>>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        answer[0]?.complete(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    return remember(context, launcher) {
        suspend {
            val wanted = listOfNotNull(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU }
            ).filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (wanted.isEmpty()) {
                true
            } else {
                val deferred = CompletableDeferred<Boolean>()
                answer[0] = deferred
                launcher.launch(wanted.toTypedArray())
                deferred.await()
            }
        }
    }
}

internal actual fun encodeJpeg(bitmap: ImageBitmap, quality: Int): ByteArray? = try {
    val stream = ByteArrayOutputStream()
    if (bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.JPEG, quality, stream)) stream.toByteArray() else null
} catch (_: Throwable) {
    null
}
