#include "fpdfview.h"
#include "fpdf_text.h"
#include "pdfium_bridge.h"
#include <cmath>
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

int32_t pdfium_get_page_text(int64_t doc_handle, int32_t page_index, uint16_t* out_text, int32_t capacity) {
    if (!doc_handle) return -1;
    std::lock_guard<std::mutex> lock(g_pdfium_mutex);
    FPDF_DOCUMENT doc = reinterpret_cast<FPDF_DOCUMENT>(doc_handle);
    FPDF_PAGE page = FPDF_LoadPage(doc, page_index);
    if (!page) return -1;
    FPDF_TEXTPAGE text_page = FPDFText_LoadPage(page);
    if (!text_page) {
        FPDF_ClosePage(page);
        return -1;
    }
    int count = FPDFText_CountChars(text_page);
    if (count > 0 && out_text && capacity > count) {
        // Writes count characters and the terminator
        FPDFText_GetText(text_page, 0, count, reinterpret_cast<unsigned short*>(out_text));
    }
    FPDFText_ClosePage(text_page);
    FPDF_ClosePage(page);
    return count < 0 ? -1 : count;
}

int32_t pdfium_get_page_text_layout(int64_t doc_handle, int32_t page_index, int32_t* out_chars, float* out_boxes, int32_t capacity) {
    if (!doc_handle) return -1;
    std::lock_guard<std::mutex> lock(g_pdfium_mutex);
    FPDF_DOCUMENT doc = reinterpret_cast<FPDF_DOCUMENT>(doc_handle);
    FPDF_PAGE page = FPDF_LoadPage(doc, page_index);
    if (!page) return -1;
    FPDF_TEXTPAGE text_page = FPDFText_LoadPage(page);
    if (!text_page) {
        FPDF_ClosePage(page);
        return -1;
    }
    int count = FPDFText_CountChars(text_page);
    if (count > 0 && out_chars && out_boxes && capacity >= count) {
        // The boxes are in the space of the PDF (origin at the bottom-left corner of the media
        // box, before the rotation of the page): the display matrix of the rendering maps them to
        // the page as it is displayed. FPDF_PageToDevice works in integers, so it is sampled at
        // 64 device pixels per point.
        const double scale = 64.0;
        int size_x = static_cast<int>(std::lround(FPDF_GetPageWidthF(page) * scale));
        int size_y = static_cast<int>(std::lround(FPDF_GetPageHeightF(page) * scale));
        int ox = 0, oy = 0, ax = 0, ay = 0, bx = 0, by = 0;
        FPDF_PageToDevice(page, 0, 0, size_x, size_y, 0, 0.0, 0.0, &ox, &oy);
        FPDF_PageToDevice(page, 0, 0, size_x, size_y, 0, 1000.0, 0.0, &ax, &ay);
        FPDF_PageToDevice(page, 0, 0, size_x, size_y, 0, 0.0, 1000.0, &bx, &by);
        // device = origin + x * u + y * v, in points
        const double unit = 1000.0 * scale;
        const double ux = (ax - ox) / unit, uy = (ay - oy) / unit;
        const double vx = (bx - ox) / unit, vy = (by - oy) / unit;
        const double tx = ox / scale, ty = oy / scale;

        for (int i = 0; i < count; i++) {
            out_chars[i] = static_cast<int32_t>(FPDFText_GetUnicode(text_page, i));
            float* box = out_boxes + i * 4;
            FS_RECTF rect;
            if (FPDFText_IsGenerated(text_page, i) == 1 || !FPDFText_GetLooseCharBox(text_page, i, &rect)) {
                box[0] = box[1] = box[2] = box[3] = 0.0f;
                continue;
            }
            const double xs[2] = {rect.left, rect.right};
            const double ys[2] = {rect.bottom, rect.top};
            double left = 0, top = 0, right = 0, bottom = 0;
            for (int c = 0; c < 4; c++) {
                const double x = xs[c & 1], y = ys[c >> 1];
                const double dx = tx + x * ux + y * vx;
                const double dy = ty + x * uy + y * vy;
                if (c == 0 || dx < left) left = dx;
                if (c == 0 || dx > right) right = dx;
                if (c == 0 || dy < top) top = dy;
                if (c == 0 || dy > bottom) bottom = dy;
            }
            box[0] = static_cast<float>(left);
            box[1] = static_cast<float>(top);
            box[2] = static_cast<float>(right);
            box[3] = static_cast<float>(bottom);
        }
    }
    FPDFText_ClosePage(text_page);
    FPDF_ClosePage(page);
    return count < 0 ? -1 : count;
}

void pdfium_close_document(int64_t doc_handle) {
    if (!doc_handle) return;
    std::lock_guard<std::mutex> lock(g_pdfium_mutex);
    FPDF_DOCUMENT doc = reinterpret_cast<FPDF_DOCUMENT>(doc_handle);
    FPDF_CloseDocument(doc);
}

}
