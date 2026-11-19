package io.github.valerioisufi.pageboard.lecture.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

// The access to the microphone is the one of the application launching the JVM
@Composable
internal actual fun rememberMicrophoneAccess(): suspend () -> Boolean = remember { suspend { true } }
