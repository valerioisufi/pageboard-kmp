package io.github.valerioisufi.drawview.sync.ui

import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.valerioisufi.drawview.core.platform.DrawViewAndroid
import io.github.valerioisufi.drawview.sync.lan.LanDiscovery
import io.github.valerioisufi.drawview.sync.lan.NsdLanDiscovery
import kotlinx.coroutines.CompletableDeferred

internal actual fun createLanDiscovery(): LanDiscovery = NsdLanDiscovery(DrawViewAndroid.context)

internal actual fun localDeviceName(): String =
    Settings.Global.getString(DrawViewAndroid.context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
        ?: Build.MODEL

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
