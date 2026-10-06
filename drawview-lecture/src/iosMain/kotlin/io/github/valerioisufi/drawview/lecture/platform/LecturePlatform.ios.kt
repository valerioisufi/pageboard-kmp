package io.github.valerioisufi.drawview.lecture.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.AVAudioApplication
import platform.AVFAudio.AVAudioApplicationRecordPermissionDenied
import platform.AVFAudio.AVAudioApplicationRecordPermissionGranted

// The models follow the lectures on the desktop only, for now
internal actual fun generationSupported(): Boolean = false

internal actual val isComputer: Boolean = false

@Composable
internal actual fun rememberMicrophoneAccess(): suspend () -> Boolean = remember {
    suspend {
        when (AVAudioApplication.sharedInstance().recordPermission) {
            AVAudioApplicationRecordPermissionGranted -> true
            AVAudioApplicationRecordPermissionDenied -> false
            else -> suspendCancellableCoroutine { continuation ->
                AVAudioApplication.requestRecordPermissionWithCompletionHandler { granted -> continuation.resume(granted) }
            }
        }
    }
}

actual fun environmentApiKey(): String? = null

// The files of the application are private to it
actual fun restrictToOwner(path: String) {}
