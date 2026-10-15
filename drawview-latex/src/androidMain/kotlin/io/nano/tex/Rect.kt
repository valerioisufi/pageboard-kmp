package io.nano.tex

/**
 * 2D bounding rectangle used by TeX text measurement (fields read through JNI).
 */
public class Rect(
    @JvmField public var x: Float = 0f,
    @JvmField public var y: Float = 0f,
    @JvmField public var w: Float = 0f,
    @JvmField public var h: Float = 0f
)
