package io.github.valerioisufi.drawview.core.engine

import io.github.valerioisufi.drawview.core.ink.InkNative
import io.github.valerioisufi.drawview.core.ink.JniInkApi

internal actual fun createStrokeEngineDelegate(): StrokeEngineDelegate =
    if (InkNative.isAvailable) NativeStrokeEngineDelegate(JniInkApi) else KotlinStrokeEngineDelegate()
