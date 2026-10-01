#include "fpdfview.h"
#include "pdfium_bridge.h"
#include <jni.h>
#include <cstring>
#include <mutex>

static std::mutex g_pdfium_mutex;
static bool g_pdfium_initialized = false;

extern "C" {

void pdfium_init(void) {
    std::lock_guard<std::mutex> lock(g_pdfium_mutex);
    if (!g_pdfium_initialized) {
        FPDF_LIBRARY_CONFIG config;
        std::memset(&config, 0, sizeof(config));
        config.version = 2;
        FPDF_InitLibraryWithConfig(&config);
        g_pdfium_initialized = true;
    }
}

void pdfium_destroy(void) {
    std::lock_guard<std::mutex> lock(g_pdfium_mutex);
    if (g_pdfium_initialized) {
        FPDF_DestroyLibrary();
        g_pdfium_initialized = false;
    }
}

int64_t pdfium_open_document(const char* file_path, const char* password) {
    pdfium_init();
    std::lock_guard<std::mutex> lock(g_pdfium_mutex);
    FPDF_DOCUMENT doc = FPDF_LoadDocument(file_path, password);
    return reinterpret_cast<int64_t>(doc);
}

int32_t pdfium_get_page_count(int64_t doc_handle) {
    if (!doc_handle) return 0;
    std::lock_guard<std::mutex> lock(g_pdfium_mutex);
    FPDF_DOCUMENT doc = reinterpret_cast<FPDF_DOCUMENT>(doc_handle);
    return FPDF_GetPageCount(doc);
}

int32_t pdfium_get_page_size(int64_t doc_handle, int32_t page_index, double* out_width_pt, double* out_height_pt) {
    if (!doc_handle || !out_width_pt || !out_height_pt) return 0;
    std::lock_guard<std::mutex> lock(g_pdfium_mutex);
    FPDF_DOCUMENT doc = reinterpret_cast<FPDF_DOCUMENT>(doc_handle);
    FS_SIZEF size;
    if (FPDF_GetPageSizeByIndexF(doc, page_index, &size)) {
        *out_width_pt = size.width;
        *out_height_pt = size.height;
        return 1;
    }
    return 0;
}

int32_t pdfium_render_page_bitmap(
    int64_t doc_handle,
    int32_t page_index,
    int32_t start_x,
    int32_t start_y,
    int32_t size_x,
    int32_t size_y,
    int32_t width_px,
    int32_t height_px,
    int32_t rotate,
    uint32_t* out_pixels
) {
    if (!doc_handle || !out_pixels || width_px <= 0 || height_px <= 0) return 0;
    std::lock_guard<std::mutex> lock(g_pdfium_mutex);
    FPDF_DOCUMENT doc = reinterpret_cast<FPDF_DOCUMENT>(doc_handle);
    FPDF_PAGE page = FPDF_LoadPage(doc, page_index);
    if (!page) return 0;

    // FPDFBitmap_BGRA with stride = width_px * 4
    FPDF_BITMAP bitmap = FPDFBitmap_CreateEx(
        width_px, height_px, FPDFBitmap_BGRA, out_pixels, width_px * 4
    );
    if (!bitmap) {
        FPDF_ClosePage(page);
        return 0;
    }

    // Fill white
    FPDFBitmap_FillRect(bitmap, 0, 0, width_px, height_px, 0xFFFFFFFF);

    int flags = FPDF_ANNOT | FPDF_REVERSE_BYTE_ORDER;
    FPDF_RenderPageBitmap(
        bitmap, page,
        start_x, start_y,
        size_x, size_y,
        rotate, flags
    );

    FPDFBitmap_Destroy(bitmap);
    FPDF_ClosePage(page);
    return 1;
}

void pdfium_close_document(int64_t doc_handle) {
    if (!doc_handle) return;
    std::lock_guard<std::mutex> lock(g_pdfium_mutex);
    FPDF_DOCUMENT doc = reinterpret_cast<FPDF_DOCUMENT>(doc_handle);
    FPDF_CloseDocument(doc);
}

// --- JNI Export Functions ---

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
