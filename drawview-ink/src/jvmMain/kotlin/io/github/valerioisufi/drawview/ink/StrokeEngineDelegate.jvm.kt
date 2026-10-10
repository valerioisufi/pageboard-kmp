package io.github.valerioisufi.drawview.ink

import io.github.valerioisufi.drawview.ink.InkNative
import io.github.valerioisufi.drawview.ink.JniInkApi
import io.github.valerioisufi.drawview.model.KotlinStrokeEngineDelegate
import io.github.valerioisufi.drawview.model.StrokeEngineDelegate

internal actual fun createStrokeEngineDelegate(): StrokeEngineDelegate =
    if (InkNative.isAvailable) NativeStrokeEngineDelegate(JniInkApi) else KotlinStrokeEngineDelegate()
