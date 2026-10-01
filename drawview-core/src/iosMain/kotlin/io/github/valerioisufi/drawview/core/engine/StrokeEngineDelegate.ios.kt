package io.github.valerioisufi.drawview.core.engine

import io.github.valerioisufi.drawview.core.ink.cinterop.ink_create_brush
import io.github.valerioisufi.drawview.core.ink.cinterop.ink_destroy_brush
import io.github.valerioisufi.drawview.core.ink.cinterop.ink_stroke_builder_add_point
import io.github.valerioisufi.drawview.core.ink.cinterop.ink_stroke_builder_clear
import io.github.valerioisufi.drawview.core.ink.cinterop.ink_stroke_builder_create
import io.github.valerioisufi.drawview.core.ink.cinterop.ink_stroke_builder_destroy
import io.github.valerioisufi.drawview.core.ink.cinterop.ink_stroke_builder_finish_inputs
import io.github.valerioisufi.drawview.core.ink.cinterop.ink_stroke_builder_get_mesh
import io.github.valerioisufi.drawview.core.ink.cinterop.ink_stroke_builder_get_triangle_count
import io.github.valerioisufi.drawview.core.ink.cinterop.ink_stroke_builder_get_vertex_count
import io.github.valerioisufi.drawview.core.ink.cinterop.ink_stroke_builder_start
import kotlinx.cinterop.COpaque
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned

/**
 * [InkNativeApi] backed by the Google Ink C++ engine linked statically through cinterop
 * (see `native/scripts/build_ios.sh`).
 */
@OptIn(ExperimentalForeignApi::class)
internal object CInteropInkApi : InkNativeApi {
    override fun createBrush(familyType: Int, size: Float, r: Float, g: Float, b: Float, a: Float, epsilon: Float): Long =
        ink_create_brush(familyType, size, r, g, b, a, epsilon).toLong()

    override fun destroyBrush(brushHandle: Long) {
        ink_destroy_brush(brushHandle.toCPointer<COpaque>())
    }

    override fun createStrokeBuilder(): Long = ink_stroke_builder_create().toLong()

    override fun startStroke(builderHandle: Long, brushHandle: Long, strokeUnitLengthCm: Float) {
        ink_stroke_builder_start(builderHandle.toCPointer<COpaque>(), brushHandle.toCPointer<COpaque>(), strokeUnitLengthCm)
    }

    override fun addPoint(builderHandle: Long, x: Float, y: Float, pressure: Float, timeMs: Long, toolType: Int) {
        ink_stroke_builder_add_point(builderHandle.toCPointer<COpaque>(), x, y, pressure, timeMs, toolType)
    }

    override fun finishInputs(builderHandle: Long) {
        ink_stroke_builder_finish_inputs(builderHandle.toCPointer<COpaque>())
    }

    override fun clearStroke(builderHandle: Long) {
        ink_stroke_builder_clear(builderHandle.toCPointer<COpaque>())
    }

    override fun destroyStrokeBuilder(builderHandle: Long) {
        ink_stroke_builder_destroy(builderHandle.toCPointer<COpaque>())
    }

    override fun getVertexCount(builderHandle: Long): Int =
        ink_stroke_builder_get_vertex_count(builderHandle.toCPointer<COpaque>())

    override fun getTriangleCount(builderHandle: Long): Int =
        ink_stroke_builder_get_triangle_count(builderHandle.toCPointer<COpaque>())

    override fun getMeshData(builderHandle: Long, outPositions: FloatArray, outIndices: IntArray): Boolean {
        if (outPositions.isEmpty() || outIndices.isEmpty()) return false
        return outPositions.usePinned { positions ->
            outIndices.usePinned { indices ->
                ink_stroke_builder_get_mesh(
                    builderHandle.toCPointer<COpaque>(),
                    positions.addressOf(0),
                    outPositions.size,
                    indices.addressOf(0).reinterpret<UIntVar>(),
                    outIndices.size
                ) == 0
            }
        }
    }
}

internal actual fun createStrokeEngineDelegate(): StrokeEngineDelegate = NativeStrokeEngineDelegate(CInteropInkApi)
