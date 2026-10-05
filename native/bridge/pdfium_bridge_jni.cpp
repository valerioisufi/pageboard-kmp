// JNI entry points of io.github.valerioisufi.drawview.pdf.PdfiumNative (desktop JVM and Android)
// on top of the plain C API of pdfium_bridge.h (also used by iOS through cinterop).
#include "pdfium_bridge.h"
#include <jni.h>
#include <vector>

extern "C" {

JNIEXPORT void JNICALL Java_io_github_valerioisufi_drawview_pdf_PdfiumNative_nInit(JNIEnv*, jclass) {
    pdfium_init();
}

JNIEXPORT void JNICALL Java_io_github_valerioisufi_drawview_pdf_PdfiumNative_nDestroy(JNIEnv*, jclass) {
    pdfium_destroy();
}

JNIEXPORT jlong JNICALL Java_io_github_valerioisufi_drawview_pdf_PdfiumNative_nOpenDocument(
    JNIEnv* env, jclass, jstring jpath, jstring jpass) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    const char* pass = jpass ? env->GetStringUTFChars(jpass, nullptr) : nullptr;
    int64_t handle = pdfium_open_document(path, pass);
    env->ReleaseStringUTFChars(jpath, path);
    if (pass) env->ReleaseStringUTFChars(jpass, pass);
    return handle;
}

JNIEXPORT jint JNICALL Java_io_github_valerioisufi_drawview_pdf_PdfiumNative_nGetPageCount(
    JNIEnv*, jclass, jlong handle) {
    return pdfium_get_page_count(handle);
}

JNIEXPORT jboolean JNICALL Java_io_github_valerioisufi_drawview_pdf_PdfiumNative_nGetPageSize(
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

JNIEXPORT jboolean JNICALL Java_io_github_valerioisufi_drawview_pdf_PdfiumNative_nRenderPageBitmap(
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

JNIEXPORT jstring JNICALL Java_io_github_valerioisufi_drawview_pdf_PdfiumNative_nGetPageText(
    JNIEnv* env, jclass, jlong handle, jint page_index) {
    int32_t length = pdfium_get_page_text(handle, page_index, nullptr, 0);
    if (length < 0) return nullptr;
    std::vector<uint16_t> text(length + 1);
    if (length > 0 && pdfium_get_page_text(handle, page_index, text.data(), length + 1) != length) return nullptr;
    return env->NewString(reinterpret_cast<const jchar*>(text.data()), length);
}

JNIEXPORT jint JNICALL Java_io_github_valerioisufi_drawview_pdf_PdfiumNative_nGetPageTextLayout(
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

JNIEXPORT void JNICALL Java_io_github_valerioisufi_drawview_pdf_PdfiumNative_nCloseDocument(
    JNIEnv*, jclass, jlong handle) {
    pdfium_close_document(handle);
}

}
