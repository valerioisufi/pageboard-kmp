package io.github.valerioisufi.pageboard.ink

import io.github.valerioisufi.pageboard.ink.InkNative
import io.github.valerioisufi.pageboard.ink.JniInkApi
import io.github.valerioisufi.pageboard.model.KotlinStrokeEngineDelegate
import io.github.valerioisufi.pageboard.model.StrokeEngineDelegate

internal actual fun createStrokeEngineDelegate(): StrokeEngineDelegate =
    if (InkNative.isAvailable) NativeStrokeEngineDelegate(JniInkApi) else KotlinStrokeEngineDelegate()
