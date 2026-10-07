package io.github.valerioisufi.drawview.lecture.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import io.github.valerioisufi.drawview.core.platform.DrawViewAndroid
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred

internal actual val isComputer: Boolean = false

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

actual fun environmentApiKey(): String? = null

// The files of the application are private to it
actual fun restrictToOwner(path: String) {}

actual fun encodeJpeg(bitmap: ImageBitmap, quality: Int): ByteArray? = try {
    val stream = ByteArrayOutputStream()
    if (bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.JPEG, quality, stream)) stream.toByteArray() else null
} catch (_: Throwable) {
    null
}

internal actual fun showNotification(channel: String, title: String, text: String) {
    val context = DrawViewAndroid.context
    // Without the permission (Android 13+), nothing is shown
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(ARRIVALS_CHANNEL, channel, NotificationManager.IMPORTANCE_DEFAULT))
    val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
        PendingIntent.getActivity(context, 1, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    val notification = Notification.Builder(context, ARRIVALS_CHANNEL)
        .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
        .setContentTitle(title)
        .setContentText(text)
        .setAutoCancel(true)
        .setContentIntent(open)
        .build()
    manager.notify((title + text).hashCode(), notification)
}

private const val ARRIVALS_CHANNEL = "lecture-arrivals"
