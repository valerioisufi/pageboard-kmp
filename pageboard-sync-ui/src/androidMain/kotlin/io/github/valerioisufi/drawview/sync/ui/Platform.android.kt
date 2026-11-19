package io.github.valerioisufi.drawview.sync.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.valerioisufi.drawview.platform.DrawViewAndroid
import io.github.valerioisufi.drawview.sync.lan.LanDiscovery
import io.github.valerioisufi.drawview.sync.lan.NsdLanDiscovery
import kotlinx.coroutines.CompletableDeferred

internal actual fun createLanDiscovery(): LanDiscovery = NsdLanDiscovery(DrawViewAndroid.context)

/** The permission of the local network, from Android 17. */
private const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
private const val ANDROID_17 = 37

@Composable
internal actual fun rememberLocalNetworkAccess(): suspend () -> Boolean {
    val context = LocalContext.current
    val answer = remember { arrayOfNulls<CompletableDeferred<Boolean>>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        answer[0]?.complete(granted)
    }
    return remember(context, launcher) {
        suspend {
            when {
                Build.VERSION.SDK_INT < ANDROID_17 -> true
                context.checkSelfPermission(ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED -> true
                else -> {
                    val deferred = CompletableDeferred<Boolean>()
                    answer[0] = deferred
                    launcher.launch(ACCESS_LOCAL_NETWORK)
                    deferred.await()
                }
            }
        }
    }
}

/**
 * Keeps the application working with the other devices in the background: a foreground service
 * (with its notification) as long as some are connected, started while the application is shown.
 */
internal actual object BackgroundSync {
    actual fun update(active: Boolean, documents: Int) {
        val context = DrawViewAndroid.context
        val intent = Intent(context, SyncService::class.java).putExtra(SyncService.DOCUMENTS, documents)
        try {
            if (active) context.startForegroundService(intent) else context.stopService(intent)
        } catch (e: IllegalStateException) {
            // Not allowed from the background: the connections go on while the system lets them
            println("DrawView: cannot keep the synchronization in the background: ${e.message}")
        }
    }
}

/** The foreground service keeping the shared documents up to date in the background (see [BackgroundSync]). */
internal class SyncService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.sync_background_channel), NotificationManager.IMPORTANCE_MIN))
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val documents = intent?.getIntExtra(DOCUMENTS, 1) ?: 1
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.sync_background_channel))
            .setContentText(getString(R.string.sync_background_notification, documents))
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        return START_NOT_STICKY
    }

    // The time Android gives to the synchronizations is over: stopped
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    internal companion object {
        const val DOCUMENTS = "documents"
        const val CHANNEL = "pageboard-sync"
        const val NOTIFICATION_ID = 4_212
    }
}
