// JNI entry points of io.github.valerioisufi.drawview.pdf.PdfiumNative (desktop JVM and Android)
// on top of the plain C API of pdfium_bridge.h (also used by iOS through cinterop).
#include "pdfium_bridge.h"
#include <jni.h>

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

JNIEXPORT void JNICALL Java_io_github_valerioisufi_drawview_pdf_PdfiumNative_nCloseDocument(
    JNIEnv*, jclass, jlong handle) {
    pdfium_close_document(handle);
}

}
