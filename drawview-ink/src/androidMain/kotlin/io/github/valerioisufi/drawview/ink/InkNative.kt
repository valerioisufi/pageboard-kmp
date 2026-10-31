package io.github.valerioisufi.drawview.ink

import io.github.valerioisufi.drawview.ink.InkNativeApi

/**
 * JNI binding of `native/bridge/ink_bridge_jni.cpp`
 * (symbols `Java_io_github_valerioisufi_drawview_ink_InkNative_*`).
 */
internal object InkNative {

    /** Whether `libink_bridge` has been loaded successfully. */
    val isAvailable: Boolean by lazy { InkNativeLoader.load() }

    @JvmStatic
    external fun createBrush(
        familyType: Int,
        size: Float,
        r: Float, g: Float, b: Float, a: Float,
        epsilon: Float
    ): Long

    @JvmStatic
    external fun destroyBrush(brushHandle: Long)

    @JvmStatic
    external fun createStrokeBuilder(): Long

    @JvmStatic
    external fun startStroke(builderHandle: Long, brushHandle: Long, strokeUnitLengthCm: Float)

    @JvmStatic
    external fun addPoint(
        builderHandle: Long,
        x: Float, y: Float,
        pressure: Float,
        timeMs: Long,
        toolType: Int
    )

    @JvmStatic
    external fun addPoints(
        builderHandle: Long,
        xs: FloatArray, ys: FloatArray,
        pressures: FloatArray,
        timesMs: LongArray,
        toolType: Int
    )

    @JvmStatic
    external fun finishInputs(builderHandle: Long)

    @JvmStatic
    external fun clearStroke(builderHandle: Long)

    @JvmStatic
    external fun destroyStrokeBuilder(builderHandle: Long)

    @JvmStatic
    external fun getVertexCount(builderHandle: Long): Int

    @JvmStatic
    external fun getTriangleCount(builderHandle: Long): Int

    @JvmStatic
    external fun getMeshData(
        builderHandle: Long,
        outPositions: FloatArray,
        outIndices: IntArray
    ): Boolean

    @JvmStatic
    external fun getOutlineCount(builderHandle: Long): Int

    @JvmStatic
    external fun getOutlineVertexCount(builderHandle: Long): Int

    @JvmStatic
    external fun getOutlines(
        builderHandle: Long,
        outPositions: FloatArray,
        outLengths: IntArray
    ): Boolean
}

/**
 * [InkNativeApi] implemented through JNI.
 */
internal object JniInkApi : InkNativeApi {
    override fun createBrush(familyType: Int, size: Float, r: Float, g: Float, b: Float, a: Float, epsilon: Float): Long =
        InkNative.createBrush(familyType, size, r, g, b, a, epsilon)

    override fun destroyBrush(brushHandle: Long) = InkNative.destroyBrush(brushHandle)

    override fun createStrokeBuilder(): Long = InkNative.createStrokeBuilder()

    override fun startStroke(builderHandle: Long, brushHandle: Long, strokeUnitLengthCm: Float) =
        InkNative.startStroke(builderHandle, brushHandle, strokeUnitLengthCm)

    override fun addPoint(builderHandle: Long, x: Float, y: Float, pressure: Float, timeMs: Long, toolType: Int) =
        InkNative.addPoint(builderHandle, x, y, pressure, timeMs, toolType)

    override fun addPoints(builderHandle: Long, xs: FloatArray, ys: FloatArray, pressures: FloatArray, timesMs: LongArray, toolType: Int) =
        InkNative.addPoints(builderHandle, xs, ys, pressures, timesMs, toolType)

    override fun finishInputs(builderHandle: Long) = InkNative.finishInputs(builderHandle)

    override fun clearStroke(builderHandle: Long) = InkNative.clearStroke(builderHandle)

    override fun destroyStrokeBuilder(builderHandle: Long) = InkNative.destroyStrokeBuilder(builderHandle)

    override fun getVertexCount(builderHandle: Long): Int = InkNative.getVertexCount(builderHandle)

    override fun getTriangleCount(builderHandle: Long): Int = InkNative.getTriangleCount(builderHandle)

    override fun getMeshData(builderHandle: Long, outPositions: FloatArray, outIndices: IntArray): Boolean =
        InkNative.getMeshData(builderHandle, outPositions, outIndices)

    override fun getOutlineCount(builderHandle: Long): Int = InkNative.getOutlineCount(builderHandle)

    override fun getOutlineVertexCount(builderHandle: Long): Int = InkNative.getOutlineVertexCount(builderHandle)

    override fun getOutlines(builderHandle: Long, outPositions: FloatArray, outLengths: IntArray): Boolean =
        InkNative.getOutlines(builderHandle, outPositions, outLengths)
}
