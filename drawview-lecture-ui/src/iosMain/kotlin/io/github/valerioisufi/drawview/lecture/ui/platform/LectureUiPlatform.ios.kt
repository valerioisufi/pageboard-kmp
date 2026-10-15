package io.github.valerioisufi.drawview.lecture.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.AVAudioApplication
import platform.AVFAudio.AVAudioApplicationRecordPermissionDenied
import platform.AVFAudio.AVAudioApplicationRecordPermissionGranted

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
