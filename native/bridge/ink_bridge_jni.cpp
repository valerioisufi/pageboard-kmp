#include "ink_bridge.h"

#include <cstdint>

#include <jni.h>

// =========================================================================
// JNI Export implementations for Kotlin Multiplatform (JVM & Android)
// Package: io.github.valerioisufi.drawview.ink.InkNative
// =========================================================================

extern "C" {

JNIEXPORT jlong JNICALL Java_io_github_valerioisufi_drawview_ink_InkNative_createBrush(
    JNIEnv*, jclass,
    jint family_type,
    jfloat size,
    jfloat r, jfloat g, jfloat b, jfloat a,
    jfloat epsilon
) {
    return reinterpret_cast<jlong>(ink_create_brush(family_type, size, r, g, b, a, epsilon));
}

JNIEXPORT void JNICALL Java_io_github_valerioisufi_drawview_ink_InkNative_destroyBrush(
    JNIEnv*, jclass,
    jlong brush_handle
) {
    ink_destroy_brush(reinterpret_cast<InkBrushHandle>(brush_handle));
}

JNIEXPORT jlong JNICALL Java_io_github_valerioisufi_drawview_ink_InkNative_createStrokeBuilder(
    JNIEnv*, jclass
) {
    return reinterpret_cast<jlong>(ink_stroke_builder_create());
}

JNIEXPORT void JNICALL Java_io_github_valerioisufi_drawview_ink_InkNative_startStroke(
    JNIEnv*, jclass,
    jlong builder_handle,
    jlong brush_handle,
    jfloat stroke_unit_length_cm
) {
    ink_stroke_builder_start(
        reinterpret_cast<InkStrokeBuilderHandle>(builder_handle),
        reinterpret_cast<InkBrushHandle>(brush_handle),
        stroke_unit_length_cm
    );
}

JNIEXPORT void JNICALL Java_io_github_valerioisufi_drawview_ink_InkNative_addPoint(
    JNIEnv*, jclass,
    jlong builder_handle,
    jfloat x, jfloat y,
    jfloat pressure,
    jlong time_ms,
    jint tool_type
) {
    ink_stroke_builder_add_point(
        reinterpret_cast<InkStrokeBuilderHandle>(builder_handle),
        x, y, pressure, static_cast<int64_t>(time_ms), static_cast<int32_t>(tool_type)
    );
}

JNIEXPORT void JNICALL Java_io_github_valerioisufi_drawview_ink_InkNative_finishInputs(
    JNIEnv*, jclass,
    jlong builder_handle
) {
    ink_stroke_builder_finish_inputs(reinterpret_cast<InkStrokeBuilderHandle>(builder_handle));
}

JNIEXPORT void JNICALL Java_io_github_valerioisufi_drawview_ink_InkNative_clearStroke(
    JNIEnv*, jclass,
    jlong builder_handle
) {
    ink_stroke_builder_clear(reinterpret_cast<InkStrokeBuilderHandle>(builder_handle));
}

JNIEXPORT void JNICALL Java_io_github_valerioisufi_drawview_ink_InkNative_destroyStrokeBuilder(
    JNIEnv*, jclass,
    jlong builder_handle
) {
    ink_stroke_builder_destroy(reinterpret_cast<InkStrokeBuilderHandle>(builder_handle));
}

JNIEXPORT jint JNICALL Java_io_github_valerioisufi_drawview_ink_InkNative_getVertexCount(
    JNIEnv*, jclass,
    jlong builder_handle
) {
    return ink_stroke_builder_get_vertex_count(reinterpret_cast<InkStrokeBuilderHandle>(builder_handle));
}

JNIEXPORT jint JNICALL Java_io_github_valerioisufi_drawview_ink_InkNative_getTriangleCount(
    JNIEnv*, jclass,
    jlong builder_handle
) {
    return ink_stroke_builder_get_triangle_count(reinterpret_cast<InkStrokeBuilderHandle>(builder_handle));
}

JNIEXPORT jboolean JNICALL Java_io_github_valerioisufi_drawview_ink_InkNative_getMeshData(
    JNIEnv* env, jclass,
    jlong builder_handle,
    jfloatArray out_positions,
    jintArray out_indices
) {
    if (builder_handle == 0 || out_positions == nullptr || out_indices == nullptr) {
        return JNI_FALSE;
    }
    jsize max_pos = env->GetArrayLength(out_positions);
    jsize max_idx = env->GetArrayLength(out_indices);

    jfloat* pos_ptr = env->GetFloatArrayElements(out_positions, nullptr);
    jint* idx_ptr = env->GetIntArrayElements(out_indices, nullptr);
    if (!pos_ptr || !idx_ptr) {
        if (pos_ptr) env->ReleaseFloatArrayElements(out_positions, pos_ptr, JNI_ABORT);
        if (idx_ptr) env->ReleaseIntArrayElements(out_indices, idx_ptr, JNI_ABORT);
        return JNI_FALSE;
    }

    int32_t res = ink_stroke_builder_get_mesh(
        reinterpret_cast<InkStrokeBuilderHandle>(builder_handle),
        pos_ptr, max_pos,
        reinterpret_cast<uint32_t*>(idx_ptr), max_idx
    );

    env->ReleaseFloatArrayElements(out_positions, pos_ptr, res == 0 ? 0 : JNI_ABORT);
    env->ReleaseIntArrayElements(out_indices, idx_ptr, res == 0 ? 0 : JNI_ABORT);

    return res == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_io_github_valerioisufi_drawview_ink_InkNative_getOutlineCount(
    JNIEnv*, jclass,
    jlong builder_handle
) {
    return ink_stroke_builder_get_outline_count(reinterpret_cast<InkStrokeBuilderHandle>(builder_handle));
}

JNIEXPORT jint JNICALL Java_io_github_valerioisufi_drawview_ink_InkNative_getOutlineVertexCount(
    JNIEnv*, jclass,
    jlong builder_handle
) {
    return ink_stroke_builder_get_outline_vertex_count(reinterpret_cast<InkStrokeBuilderHandle>(builder_handle));
}

JNIEXPORT jboolean JNICALL Java_io_github_valerioisufi_drawview_ink_InkNative_getOutlines(
    JNIEnv* env, jclass,
    jlong builder_handle,
    jfloatArray out_positions,
    jintArray out_lengths
) {
    if (builder_handle == 0 || out_positions == nullptr || out_lengths == nullptr) {
        return JNI_FALSE;
    }
    jsize max_pos = env->GetArrayLength(out_positions);
    jsize max_outlines = env->GetArrayLength(out_lengths);

    jfloat* pos_ptr = env->GetFloatArrayElements(out_positions, nullptr);
    jint* len_ptr = env->GetIntArrayElements(out_lengths, nullptr);
    if (!pos_ptr || !len_ptr) {
        if (pos_ptr) env->ReleaseFloatArrayElements(out_positions, pos_ptr, JNI_ABORT);
        if (len_ptr) env->ReleaseIntArrayElements(out_lengths, len_ptr, JNI_ABORT);
        return JNI_FALSE;
    }

    int32_t res = ink_stroke_builder_get_outlines(
        reinterpret_cast<InkStrokeBuilderHandle>(builder_handle),
        pos_ptr, max_pos,
        reinterpret_cast<int32_t*>(len_ptr), max_outlines
    );

    env->ReleaseFloatArrayElements(out_positions, pos_ptr, res == 0 ? 0 : JNI_ABORT);
    env->ReleaseIntArrayElements(out_lengths, len_ptr, res == 0 ? 0 : JNI_ABORT);

    return res == 0 ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
