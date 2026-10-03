#include "fpdfview.h"
#include "pdfium_bridge.h"
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

}
