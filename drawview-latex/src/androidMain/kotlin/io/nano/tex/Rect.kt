package io.nano.tex

/**
 * 2D bounding rectangle used by TeX text measurement (fields read through JNI).
 */
class Rect(
    @JvmField var x: Float = 0f,
    @JvmField var y: Float = 0f,
    @JvmField var w: Float = 0f,
    @JvmField var h: Float = 0f
)
