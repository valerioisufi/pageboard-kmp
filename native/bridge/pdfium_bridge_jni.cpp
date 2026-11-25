// JNI entry points of io.github.valerioisufi.pageboard.pdf.PdfiumNative (desktop JVM and Android)
// on top of the plain C API of pdfium_bridge.h (also used by iOS through cinterop).
#include "pdfium_bridge.h"
#include <jni.h>
#include <vector>

extern "C" {

JNIEXPORT jlong JNICALL Java_io_github_valerioisufi_pageboard_pdf_PdfiumNative_nOpenDocument(
    JNIEnv* env, jclass, jstring jpath, jstring jpass) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    const char* pass = jpass ? env->GetStringUTFChars(jpass, nullptr) : nullptr;
    int64_t handle = pdfium_open_document(path, pass);
    env->ReleaseStringUTFChars(jpath, path);
    if (pass) env->ReleaseStringUTFChars(jpass, pass);
    return handle;
}

JNIEXPORT jint JNICALL Java_io_github_valerioisufi_pageboard_pdf_PdfiumNative_nGetPageCount(
    JNIEnv*, jclass, jlong handle) {
    return pdfium_get_page_count(handle);
}

JNIEXPORT jboolean JNICALL Java_io_github_valerioisufi_pageboard_pdf_PdfiumNative_nGetPageSize(
    JNIEnv* env, jclass, jlong handle, jint page_index, jdoubleArray out_size) {
    double w = 0, h = 0;
    int32_t res = pdfium_get_page_size(handle, page_index, &w, &h);
    if (res) {
        double buf[2] = {w, h};
        env->SetDoubleArrayRegion(out_size, 0, 2, buf);
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_io_github_valerioisufi_pageboard_pdf_PdfiumNative_nRenderPageBitmap(
    JNIEnv* env, jclass, jlong handle, jint page_index,
    jint start_x, jint start_y, jint size_x, jint size_y,
    jint width_px, jint height_px, jint rotate, jintArray out_pixels) {
    jint* ptr = env->GetIntArrayElements(out_pixels, nullptr);
    int32_t res = pdfium_render_page_bitmap(
        handle, page_index,
        start_x, start_y, size_x, size_y,
        width_px, height_px, rotate,
        reinterpret_cast<uint32_t*>(ptr)
    );
    env->ReleaseIntArrayElements(out_pixels, ptr, 0);
    return res ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_io_github_valerioisufi_pageboard_pdf_PdfiumNative_nGetPageText(
    JNIEnv* env, jclass, jlong handle, jint page_index) {
    int32_t length = pdfium_get_page_text(handle, page_index, nullptr, 0);
    if (length < 0) return nullptr;
    std::vector<uint16_t> text(length + 1);
    if (length > 0 && pdfium_get_page_text(handle, page_index, text.data(), length + 1) != length) return nullptr;
    return env->NewString(reinterpret_cast<const jchar*>(text.data()), length);
}

JNIEXPORT jint JNICALL Java_io_github_valerioisufi_pageboard_pdf_PdfiumNative_nGetPageTextLayout(
    JNIEnv* env, jclass, jlong handle, jint page_index, jintArray out_chars, jfloatArray out_boxes) {
    if (!out_chars || !out_boxes) return pdfium_get_page_text_layout(handle, page_index, nullptr, nullptr, 0);
    jsize capacity = env->GetArrayLength(out_chars);
    if (env->GetArrayLength(out_boxes) < capacity * 4) return -1;
    jint* chars = env->GetIntArrayElements(out_chars, nullptr);
    jfloat* boxes = env->GetFloatArrayElements(out_boxes, nullptr);
    int32_t count = pdfium_get_page_text_layout(
        handle, page_index, reinterpret_cast<int32_t*>(chars), reinterpret_cast<float*>(boxes), capacity
    );
    env->ReleaseFloatArrayElements(out_boxes, boxes, 0);
    env->ReleaseIntArrayElements(out_chars, chars, 0);
    return count;
}

JNIEXPORT void JNICALL Java_io_github_valerioisufi_pageboard_pdf_PdfiumNative_nCloseDocument(
    JNIEnv*, jclass, jlong handle) {
    pdfium_close_document(handle);
}

// ==========================================================
// Writing
// ==========================================================

JNIEXPORT jlong JNICALL Java_io_github_valerioisufi_pageboard_pdf_PdfiumNative_nNewDocument(JNIEnv*, jclass) {
    return pdfium_new_document();
}

JNIEXPORT jlong JNICALL Java_io_github_valerioisufi_pageboard_pdf_PdfiumNative_nNewPage(
    JNIEnv*, jclass, jlong doc, jint index, jdouble width_pt, jdouble height_pt) {
    return pdfium_new_page(doc, index, width_pt, height_pt);
}

JNIEXPORT jboolean JNICALL Java_io_github_valerioisufi_pageboard_pdf_PdfiumNative_nImportPages(
    JNIEnv* env, jclass, jlong doc, jlong source, jintArray jindices) {
    if (!jindices) return JNI_FALSE;
    jsize count = env->GetArrayLength(jindices);
    jint* indices = env->GetIntArrayElements(jindices, nullptr);
    if (!indices) return JNI_FALSE;
    int32_t res = pdfium_import_pages(doc, source, reinterpret_cast<const int32_t*>(indices), count);
    env->ReleaseIntArrayElements(jindices, indices, JNI_ABORT);
    return res ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL Java_io_github_valerioisufi_pageboard_pdf_PdfiumNative_nPlacePage(
    JNIEnv* env, jclass, jlong doc, jint from_index, jint to_index, jdoubleArray jmatrix) {
    if (!jmatrix || env->GetArrayLength(jmatrix) < 6) return 0;
    double matrix[6];
    int64_t page = pdfium_place_page(doc, from_index, to_index, matrix);
    if (page) env->SetDoubleArrayRegion(jmatrix, 0, 6, matrix);
    return page;
}

JNIEXPORT jboolean JNICALL Java_io_github_valerioisufi_pageboard_pdf_PdfiumNative_nDeletePage(JNIEnv*, jclass, jlong doc, jint index) {
    return pdfium_delete_page(doc, index) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_io_github_valerioisufi_pageboard_pdf_PdfiumNative_nPageAddPath(
    JNIEnv* env, jclass, jlong page, jbyteArray jverbs, jint verb_count, jfloatArray jpoints,
    jint fill_argb, jint stroke_argb, jfloat stroke_width) {
    if (!jverbs || !jpoints || env->GetArrayLength(jverbs) < verb_count) return JNI_FALSE;
    jbyte* verbs = env->GetByteArrayElements(jverbs, nullptr);
    jfloat* points = env->GetFloatArrayElements(jpoints, nullptr);
    int32_t res = verbs && points ? pdfium_page_add_path(
        page, reinterpret_cast<const uint8_t*>(verbs), verb_count, points,
        static_cast<uint32_t>(fill_argb), static_cast<uint32_t>(stroke_argb), stroke_width
    ) : 0;
    if (points) env->ReleaseFloatArrayElements(jpoints, points, JNI_ABORT);
    if (verbs) env->ReleaseByteArrayElements(jverbs, verbs, JNI_ABORT);
    return res ? JNI_TRUE : JNI_FALSE;
}

/** The six values of [jmatrix] into [out], or false. */
static bool read_matrix(JNIEnv* env, jdoubleArray jmatrix, double* out) {
    if (!jmatrix || env->GetArrayLength(jmatrix) < 6) return false;
    env->GetDoubleArrayRegion(jmatrix, 0, 6, out);
    return true;
}

JNIEXPORT jboolean JNICALL Java_io_github_valerioisufi_pageboard_pdf_PdfiumNative_nPageAddJpeg(
    JNIEnv* env, jclass, jlong doc, jlong page, jbyteArray jdata, jdoubleArray jmatrix) {
    double matrix[6];
    if (!jdata || !read_matrix(env, jmatrix, matrix)) return JNI_FALSE;
    jbyte* data = env->GetByteArrayElements(jdata, nullptr);
    if (!data) return JNI_FALSE;
    int32_t res = pdfium_page_add_jpeg(doc, page, reinterpret_cast<const uint8_t*>(data), env->GetArrayLength(jdata), matrix);
    env->ReleaseByteArrayElements(jdata, data, JNI_ABORT);
    return res ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_io_github_valerioisufi_pageboard_pdf_PdfiumNative_nPageAddBitmap(
    JNIEnv* env, jclass, jlong doc, jlong page, jintArray jpixels, jint width, jint height, jdoubleArray jmatrix) {
    double matrix[6];
    if (!jpixels || width <= 0 || height <= 0 || env->GetArrayLength(jpixels) < width * height || !read_matrix(env, jmatrix, matrix)) {
        return JNI_FALSE;
    }
    jint* pixels = env->GetIntArrayElements(jpixels, nullptr);
    if (!pixels) return JNI_FALSE;
    int32_t res = pdfium_page_add_bitmap(doc, page, reinterpret_cast<const uint32_t*>(pixels), width, height, matrix);
    env->ReleaseIntArrayElements(jpixels, pixels, JNI_ABORT);
    return res ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_io_github_valerioisufi_pageboard_pdf_PdfiumNative_nPageAddPdfPage(
    JNIEnv* env, jclass, jlong doc, jlong page, jlong source, jint page_index, jdoubleArray jmatrix) {
    double matrix[6];
    if (!read_matrix(env, jmatrix, matrix)) return JNI_FALSE;
    return pdfium_page_add_pdf_page(doc, page, source, page_index, matrix) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_io_github_valerioisufi_pageboard_pdf_PdfiumNative_nPageAddMarkup(
    JNIEnv* env, jclass, jlong page, jint kind, jfloatArray jquads, jint color, jstring jappearance) {
    if (!jquads) return JNI_FALSE;
    jsize count = env->GetArrayLength(jquads) / 8;
    const char* appearance = jappearance ? env->GetStringUTFChars(jappearance, nullptr) : nullptr;
    if (jappearance && !appearance) return JNI_FALSE;
    jfloat* quads = env->GetFloatArrayElements(jquads, nullptr);
    int32_t res = quads ? pdfium_page_add_markup(page, kind, quads, count, static_cast<uint32_t>(color), appearance) : 0;
    if (quads) env->ReleaseFloatArrayElements(jquads, quads, JNI_ABORT);
    if (appearance) env->ReleaseStringUTFChars(jappearance, appearance);
    return res ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_io_github_valerioisufi_pageboard_pdf_PdfiumNative_nPageAddLink(
    JNIEnv* env, jclass, jlong page, jfloatArray jquads, jstring juri) {
    if (!jquads || !juri) return JNI_FALSE;
    jsize count = env->GetArrayLength(jquads) / 8;
    const char* uri = env->GetStringUTFChars(juri, nullptr);
    if (!uri) return JNI_FALSE;
    jfloat* quads = env->GetFloatArrayElements(jquads, nullptr);
    int32_t res = quads ? pdfium_page_add_link(page, quads, count, uri) : 0;
    if (quads) env->ReleaseFloatArrayElements(jquads, quads, JNI_ABORT);
    env->ReleaseStringUTFChars(juri, uri);
    return res ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL Java_io_github_valerioisufi_pageboard_pdf_PdfiumNative_nLoadFont(
    JNIEnv* env, jclass, jlong doc, jbyteArray jdata) {
    if (!jdata) return 0;
    jbyte* data = env->GetByteArrayElements(jdata, nullptr);
    if (!data) return 0;
    int64_t font = pdfium_load_font(doc, reinterpret_cast<const uint8_t*>(data), env->GetArrayLength(jdata));
    env->ReleaseByteArrayElements(jdata, data, JNI_ABORT);
    return font;
}

JNIEXPORT void JNICALL Java_io_github_valerioisufi_pageboard_pdf_PdfiumNative_nCloseFont(JNIEnv*, jclass, jlong font) {
    pdfium_close_font(font);
}

JNIEXPORT jboolean JNICALL Java_io_github_valerioisufi_pageboard_pdf_PdfiumNative_nPageAddText(
    JNIEnv* env, jclass, jlong doc, jlong page, jlong font, jstring jtext, jfloat font_size, jint argb, jboolean invisible,
    jdoubleArray jmatrix) {
    double matrix[6];
    if (!jtext || !read_matrix(env, jmatrix, matrix)) return JNI_FALSE;
    jsize length = env->GetStringLength(jtext);
    const jchar* text = env->GetStringChars(jtext, nullptr);
    if (!text) return JNI_FALSE;
    int32_t res = pdfium_page_add_text(
        doc, page, font, reinterpret_cast<const uint16_t*>(text), length, font_size, static_cast<uint32_t>(argb),
        invisible ? 1 : 0, matrix
    );
    env->ReleaseStringChars(jtext, text);
    return res ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_io_github_valerioisufi_pageboard_pdf_PdfiumNative_nEndPage(JNIEnv*, jclass, jlong page) {
    return pdfium_end_page(page) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_io_github_valerioisufi_pageboard_pdf_PdfiumNative_nSaveDocument(
    JNIEnv* env, jclass, jlong doc, jstring jpath, jstring jinfo) {
    if (!jpath) return JNI_FALSE;
    const char* info = jinfo ? env->GetStringUTFChars(jinfo, nullptr) : nullptr;
    if (jinfo && !info) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    int32_t res = path ? pdfium_save_document(doc, path, info) : 0;
    if (path) env->ReleaseStringUTFChars(jpath, path);
    if (info) env->ReleaseStringUTFChars(jinfo, info);
    return res ? JNI_TRUE : JNI_FALSE;
}

}
