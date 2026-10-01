package io.nano.tex

import kotlin.jvm.JvmField

/**
 * 2D bounding rectangle used by TeX text measurement (fields read through JNI on the JVM).
 */
class Rect(
    @JvmField var x: Float = 0f,
    @JvmField var y: Float = 0f,
    @JvmField var w: Float = 0f,
    @JvmField var h: Float = 0f
) {
    override fun toString(): String = "Rect(x=$x, y=$y, w=$w, h=$h)"
}
