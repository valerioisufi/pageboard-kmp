#include "fpdfview.h"
#include "fpdf_annot.h"
#include "fpdf_doc.h"
#include "fpdf_edit.h"
#include "fpdf_flatten.h"
#include "fpdf_ppo.h"
#include "fpdf_save.h"
#include "fpdf_text.h"
#include "fpdf_transformpage.h"
#include "pdfium_bridge.h"
#include <algorithm>
#include <cctype>
#include <cstdlib>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

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

// ==========================================================
// Writing
// ==========================================================

static void set_matrix(FPDF_PAGEOBJECT object, const double* m) {
    FS_MATRIX matrix = {static_cast<float>(m[0]), static_cast<float>(m[1]), static_cast<float>(m[2]),
                        static_cast<float>(m[3]), static_cast<float>(m[4]), static_cast<float>(m[5])};
    FPDFPageObj_TransformF(object, &matrix);
}

int64_t pdfium_new_document(void) {
    pdfium_init();
    std::lock_guard<std::mutex> lock(g_pdfium_mutex);
    return reinterpret_cast<int64_t>(FPDF_CreateNewDocument());
}

int64_t pdfium_new_page(int64_t doc_handle, int32_t index, double width_pt, double height_pt) {
    if (!doc_handle || width_pt <= 0 || height_pt <= 0) return 0;
    std::lock_guard<std::mutex> lock(g_pdfium_mutex);
    FPDF_DOCUMENT doc = reinterpret_cast<FPDF_DOCUMENT>(doc_handle);
    if (index < 0 || index > FPDF_GetPageCount(doc)) return 0;
    return reinterpret_cast<int64_t>(FPDFPage_New(doc, index, width_pt, height_pt));
}

namespace {
/** A link of a page to the web: its area and its address. */
struct WebLink {
    FS_RECTF rect;
    std::vector<FS_QUADPOINTSF> quads;
    std::string uri;
};

/** The links of [page] (of [doc]) to the web into [links]. */
void web_links(FPDF_DOCUMENT doc, FPDF_PAGE page, std::vector<WebLink>& links) {
    int position = 0;
    FPDF_LINK link = nullptr;
    while (FPDFLink_Enumerate(page, &position, &link)) {
        FPDF_ACTION action = FPDFLink_GetAction(link);
        if (!action || FPDFAction_GetType(action) != PDFACTION_URI) continue;
        // Its length with the terminator
        const unsigned long length = FPDFAction_GetURIPath(doc, action, nullptr, 0);
        if (length <= 1) continue;
        std::vector<char> uri(length);
        if (FPDFAction_GetURIPath(doc, action, uri.data(), length) != length) continue;
        WebLink web;
        if (!FPDFLink_GetAnnotRect(link, &web.rect)) continue;
        web.uri.assign(uri.data(), length - 1);
        const int quad_count = FPDFLink_CountQuadPoints(link);
        for (int i = 0; i < quad_count; i++) {
            FS_QUADPOINTSF quad;
            if (FPDFLink_GetQuadPoints(link, i, &quad)) web.quads.push_back(quad);
        }
        links.push_back(std::move(web));
    }
}

/** Adds [link] to [page], without a border (the one it had, if drawn, is part of the page). */
bool add_web_link(FPDF_PAGE page, const WebLink& link) {
    FPDF_ANNOTATION annot = FPDFPage_CreateAnnot(page, FPDF_ANNOT_LINK);
    if (!annot) return false;
    bool ok = FPDFAnnot_SetRect(annot, &link.rect) && FPDFAnnot_SetURI(annot, link.uri.c_str()) && FPDFAnnot_SetBorder(annot, 0, 0, 0);
    for (const FS_QUADPOINTSF& quad : link.quads) ok = ok && FPDFAnnot_AppendAttachmentPoints(annot, &quad);
    const int index = ok ? -1 : FPDFPage_GetAnnotIndex(page, annot);
    FPDFPage_CloseAnnot(annot);
    if (index >= 0) FPDFPage_RemoveAnnot(page, index);
    return ok;
}

/**
 * The matrix from the points of a page as it is displayed (from its top-left corner, y going
 * down) to its user space, from its [box] as it is displayed and its [rotation] (quarter turns).
 */
void display_to_user(const FS_RECTF& box, int rotation, double* m) {
    const double l = box.left, b = box.bottom, r = box.right, t = box.top;
    double values[6];
    switch (rotation) {
        case 1: values[0] = 0; values[1] = 1; values[2] = 1; values[3] = 0; values[4] = l; values[5] = b; break;
        case 2: values[0] = -1; values[1] = 0; values[2] = 0; values[3] = 1; values[4] = r; values[5] = b; break;
        case 3: values[0] = 0; values[1] = -1; values[2] = -1; values[3] = 0; values[4] = r; values[5] = t; break;
        default: values[0] = 1; values[1] = 0; values[2] = 0; values[3] = -1; values[4] = l; values[5] = t; break;
    }
    std::memcpy(m, values, sizeof(values));
}
}  // namespace

int32_t pdfium_import_pages(int64_t doc_handle, int64_t source_handle, const int32_t* page_indices, int32_t count) {
    if (!doc_handle || !source_handle || !page_indices || count <= 0) return 0;
    std::lock_guard<std::mutex> lock(g_pdfium_mutex);
    FPDF_DOCUMENT doc = reinterpret_cast<FPDF_DOCUMENT>(doc_handle);
    FPDF_DOCUMENT source = reinterpret_cast<FPDF_DOCUMENT>(source_handle);
    const int source_count = FPDF_GetPageCount(source);
    std::vector<int> indices(page_indices, page_indices + count);
    std::vector<std::vector<WebLink>> links(count);
    for (int32_t i = 0; i < count; i++) {
        if (indices[i] < 0 || indices[i] >= source_count) return 0;
        FPDF_PAGE page = FPDF_LoadPage(source, indices[i]);
        if (!page) return 0;
        web_links(source, page, links[i]);
        // The annotations shown with the page (FPDF_ANNOT when rendering) become part of it; those
        // left (without an appearance, as the links usually are) are removed, not imported with
        // what they refer to (the other pages of the source, its form)
        FPDFPage_Flatten(page, FLAT_NORMALDISPLAY);
        for (int k = FPDFPage_GetAnnotCount(page) - 1; k >= 0; k--) FPDFPage_RemoveAnnot(page, k);
        FPDF_ClosePage(page);
    }
    const int first = FPDF_GetPageCount(doc);
    if (!FPDF_ImportPagesByIndex(doc, source, indices.data(), static_cast<unsigned long>(count), first)) {
        while (FPDF_GetPageCount(doc) > first) FPDFPage_Delete(doc, first);
        return 0;
    }
    for (int32_t i = 0; i < count; i++) {
        if (links[i].empty()) continue;
        FPDF_PAGE page = FPDF_LoadPage(doc, first + i);
        if (!page) continue;
        for (const WebLink& link : links[i]) add_web_link(page, link);
        FPDF_ClosePage(page);
    }
    return 1;
}

int64_t pdfium_place_page(int64_t doc_handle, int32_t from_index, int32_t to_index, double* out_matrix) {
    if (!doc_handle || !out_matrix) return 0;
    std::lock_guard<std::mutex> lock(g_pdfium_mutex);
    FPDF_DOCUMENT doc = reinterpret_cast<FPDF_DOCUMENT>(doc_handle);
    int from = from_index;
    if (from != to_index && !FPDF_MovePages(doc, &from, 1, to_index)) return 0;
    FPDF_PAGE page = FPDF_LoadPage(doc, to_index);
    if (!page) return 0;
    // Its content between q and Q: what is added after it starts from the default state. Loaded
    // again to be parsed so (a page without content has nothing to enclose)
    const FS_MATRIX identity = {1, 0, 0, 1, 0, 0};
    if (FPDFPage_TransFormWithClip(page, &identity, nullptr)) {
        FPDF_ClosePage(page);
        page = FPDF_LoadPage(doc, to_index);
        if (!page) return 0;
    }
    FS_RECTF box;
    if (!FPDF_GetPageBoundingBox(page, &box)) {
        FPDF_ClosePage(page);
        return 0;
    }
    display_to_user(box, FPDFPage_GetRotation(page), out_matrix);
    return reinterpret_cast<int64_t>(page);
}

int32_t pdfium_delete_page(int64_t doc_handle, int32_t index) {
    if (!doc_handle) return 0;
    std::lock_guard<std::mutex> lock(g_pdfium_mutex);
    FPDF_DOCUMENT doc = reinterpret_cast<FPDF_DOCUMENT>(doc_handle);
    if (index < 0 || index >= FPDF_GetPageCount(doc)) return 0;
    FPDFPage_Delete(doc, index);
    return 1;
}

int32_t pdfium_page_add_path(
    int64_t page_handle,
    const uint8_t* verbs,
    int32_t verb_count,
    const float* points,
    uint32_t fill_argb,
    uint32_t stroke_argb,
    float stroke_width
) {
    if (!page_handle || !verbs || !points || verb_count <= 0 || verbs[0] != 0) return 0;
    std::lock_guard<std::mutex> lock(g_pdfium_mutex);
    FPDF_PAGEOBJECT path = FPDFPageObj_CreateNewPath(points[0], points[1]);
    if (!path) return 0;
    const float* p = points + 2;
    bool ok = true;
    for (int32_t i = 1; i < verb_count && ok; i++) {
        switch (verbs[i]) {
            case 0: ok = FPDFPath_MoveTo(path, p[0], p[1]); p += 2; break;
            case 1: ok = FPDFPath_LineTo(path, p[0], p[1]); p += 2; break;
            case 2: ok = FPDFPath_BezierTo(path, p[0], p[1], p[2], p[3], p[4], p[5]); p += 6; break;
            case 3: ok = FPDFPath_Close(path); break;
            default: ok = false;
        }
    }
    const bool fill = (fill_argb >> 24) != 0;
    const bool stroke = (stroke_argb >> 24) != 0 && stroke_width > 0;
    if (fill) {
        ok = ok && FPDFPageObj_SetFillColor(path, (fill_argb >> 16) & 0xFF, (fill_argb >> 8) & 0xFF, fill_argb & 0xFF, fill_argb >> 24);
    }
    if (stroke) {
        ok = ok && FPDFPageObj_SetStrokeColor(path, (stroke_argb >> 16) & 0xFF, (stroke_argb >> 8) & 0xFF, stroke_argb & 0xFF, stroke_argb >> 24);
        ok = ok && FPDFPageObj_SetStrokeWidth(path, stroke_width);
    }
    ok = ok && FPDFPath_SetDrawMode(path, fill ? FPDF_FILLMODE_WINDING : FPDF_FILLMODE_NONE, stroke);
    if (!ok) {
        FPDFPageObj_Destroy(path);
        return 0;
    }
    FPDFPage_InsertObject(reinterpret_cast<FPDF_PAGE>(page_handle), path);
    return 1;
}

namespace {
struct MemoryFile {
    const uint8_t* data;
    unsigned long size;
};

int get_block(void* param, unsigned long position, unsigned char* buffer, unsigned long size) {
    auto* file = static_cast<MemoryFile*>(param);
    if (position > file->size || size > file->size - position) return 0;
    std::memcpy(buffer, file->data + position, size);
    return 1;
}
}  // namespace

int32_t pdfium_page_add_jpeg(int64_t doc_handle, int64_t page_handle, const uint8_t* data, int32_t size, const double* matrix) {
    if (!doc_handle || !page_handle || !data || size <= 0 || !matrix) return 0;
    std::lock_guard<std::mutex> lock(g_pdfium_mutex);
    FPDF_PAGE page = reinterpret_cast<FPDF_PAGE>(page_handle);
    FPDF_PAGEOBJECT image = FPDFPageObj_NewImageObj(reinterpret_cast<FPDF_DOCUMENT>(doc_handle));
    if (!image) return 0;
    MemoryFile file = {data, static_cast<unsigned long>(size)};
    FPDF_FILEACCESS access;
    access.m_FileLen = file.size;
    access.m_GetBlock = get_block;
    access.m_Param = &file;
    if (!FPDFImageObj_LoadJpegFileInline(&page, 1, image, &access)) {
        FPDFPageObj_Destroy(image);
        return 0;
    }
    set_matrix(image, matrix);
    FPDFPage_InsertObject(page, image);
    return 1;
}

int32_t pdfium_page_add_bitmap(int64_t doc_handle, int64_t page_handle, const uint32_t* bgra, int32_t width, int32_t height, const double* matrix) {
    if (!doc_handle || !page_handle || !bgra || width <= 0 || height <= 0 || !matrix) return 0;
    std::lock_guard<std::mutex> lock(g_pdfium_mutex);
    FPDF_PAGE page = reinterpret_cast<FPDF_PAGE>(page_handle);
    FPDF_PAGEOBJECT image = FPDFPageObj_NewImageObj(reinterpret_cast<FPDF_DOCUMENT>(doc_handle));
    if (!image) return 0;
    // The pixels are copied into the image object
    FPDF_BITMAP bitmap = FPDFBitmap_CreateEx(width, height, FPDFBitmap_BGRA, const_cast<uint32_t*>(bgra), width * 4);
    const bool ok = bitmap && FPDFImageObj_SetBitmap(&page, 1, image, bitmap);
    if (bitmap) FPDFBitmap_Destroy(bitmap);
    if (!ok) {
        FPDFPageObj_Destroy(image);
        return 0;
    }
    set_matrix(image, matrix);
    FPDFPage_InsertObject(page, image);
    return 1;
}

int32_t pdfium_page_add_pdf_page(int64_t doc_handle, int64_t page_handle, int64_t source_handle, int32_t page_index, const double* matrix) {
    if (!doc_handle || !page_handle || !source_handle || !matrix) return 0;
    std::lock_guard<std::mutex> lock(g_pdfium_mutex);
    FPDF_DOCUMENT source = reinterpret_cast<FPDF_DOCUMENT>(source_handle);
    // The annotations shown with the page (FPDF_ANNOT when rendering) become part of it
    FPDF_PAGE source_page = FPDF_LoadPage(source, page_index);
    if (!source_page) return 0;
    FPDFPage_Flatten(source_page, FLAT_NORMALDISPLAY);
    FPDF_ClosePage(source_page);

    FPDF_XOBJECT xobject = FPDF_NewXObjectFromPage(reinterpret_cast<FPDF_DOCUMENT>(doc_handle), source, page_index);
    if (!xobject) return 0;
    FPDF_PAGEOBJECT form = FPDF_NewFormObjectFromXObject(xobject);
    FPDF_CloseXObject(xobject);
    if (!form) return 0;
    set_matrix(form, matrix);
    FPDFPage_InsertObject(reinterpret_cast<FPDF_PAGE>(page_handle), form);
    return 1;
}

int32_t pdfium_page_add_markup(int64_t page_handle, int32_t kind, const float* quads, int32_t quad_count, uint32_t argb, const char* appearance) {
    if (!page_handle || !quads || quad_count <= 0) return 0;
    FPDF_ANNOTATION_SUBTYPE subtype;
    switch (kind) {
        case 0: subtype = FPDF_ANNOT_HIGHLIGHT; break;
        case 1: subtype = FPDF_ANNOT_UNDERLINE; break;
        case 2: subtype = FPDF_ANNOT_STRIKEOUT; break;
        default: return 0;
    }
    std::lock_guard<std::mutex> lock(g_pdfium_mutex);
    FPDF_ANNOTATION annot = FPDFPage_CreateAnnot(reinterpret_cast<FPDF_PAGE>(page_handle), subtype);
    if (!annot) return 0;
    bool ok = FPDFAnnot_SetColor(annot, FPDFANNOT_COLORTYPE_Color, (argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, argb >> 24);
    // Printed with the page (not by default)
    ok = ok && FPDFAnnot_SetFlags(annot, FPDF_ANNOT_FLAG_PRINT);
    float left = quads[0], right = quads[0], bottom = quads[1], top = quads[1];
    for (int32_t i = 0; i < quad_count && ok; i++) {
        const float* q = quads + i * 8;
        FS_QUADPOINTSF points = {q[0], q[1], q[2], q[3], q[4], q[5], q[6], q[7]};
        ok = FPDFAnnot_AppendAttachmentPoints(annot, &points);
        for (int c = 0; c < 4; c++) {
            left = std::min(left, q[c * 2]);
            right = std::max(right, q[c * 2]);
            bottom = std::min(bottom, q[c * 2 + 1]);
            top = std::max(top, q[c * 2 + 1]);
        }
    }
    FS_RECTF rect = {left, top, right, bottom};
    ok = ok && FPDFAnnot_SetRect(annot, &rect);
    if (ok && appearance) {
        // Its box is the rectangle of the annotation, its space the one of the page. PDFium gives
        // it resources (the opacity of the annotation) only when the annotation is translucent,
        // and PDFKit draws one without resources in black: an opaque one is made opaque to 254/255
        // (its color cannot be set again once it has an appearance)
        if ((argb >> 24) == 0xFF) ok = FPDFAnnot_SetColor(annot, FPDFANNOT_COLORTYPE_Color, (argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, 0xFE);
        std::vector<unsigned short> wide(appearance, appearance + std::strlen(appearance));
        wide.push_back(0);
        ok = ok && FPDFAnnot_SetAP(annot, FPDF_ANNOT_APPEARANCEMODE_NORMAL, reinterpret_cast<FPDF_WIDESTRING>(wide.data()));
    }
    FPDFPage_CloseAnnot(annot);
    return ok ? 1 : 0;
}

int64_t pdfium_load_font(int64_t doc_handle, const uint8_t* data, int32_t size) {
    if (!doc_handle || !data || size <= 0) return 0;
    std::lock_guard<std::mutex> lock(g_pdfium_mutex);
    return reinterpret_cast<int64_t>(
        FPDFText_LoadFont(reinterpret_cast<FPDF_DOCUMENT>(doc_handle), data, static_cast<uint32_t>(size), FPDF_FONT_TRUETYPE, 1)
    );
}

void pdfium_close_font(int64_t font_handle) {
    if (!font_handle) return;
    std::lock_guard<std::mutex> lock(g_pdfium_mutex);
    FPDFFont_Close(reinterpret_cast<FPDF_FONT>(font_handle));
}

int32_t pdfium_page_add_text(
    int64_t doc_handle,
    int64_t page_handle,
    int64_t font_handle,
    const uint16_t* utf16,
    int32_t length,
    float font_size,
    uint32_t argb,
    int32_t invisible,
    const double* matrix
) {
    if (!doc_handle || !page_handle || !font_handle || !utf16 || length <= 0 || font_size <= 0 || !matrix) return 0;
    std::lock_guard<std::mutex> lock(g_pdfium_mutex);
    FPDF_PAGEOBJECT text = FPDFPageObj_CreateTextObj(
        reinterpret_cast<FPDF_DOCUMENT>(doc_handle), reinterpret_cast<FPDF_FONT>(font_handle), font_size
    );
    if (!text) return 0;
    std::vector<uint16_t> terminated(utf16, utf16 + length);
    terminated.push_back(0);
    const bool ok = FPDFText_SetText(text, reinterpret_cast<FPDF_WIDESTRING>(terminated.data())) &&
        FPDFTextObj_SetTextRenderMode(text, invisible ? FPDF_TEXTRENDERMODE_INVISIBLE : FPDF_TEXTRENDERMODE_FILL) &&
        FPDFPageObj_SetFillColor(text, (argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, argb >> 24);
    if (!ok) {
        FPDFPageObj_Destroy(text);
        return 0;
    }
    set_matrix(text, matrix);
    FPDFPage_InsertObject(reinterpret_cast<FPDF_PAGE>(page_handle), text);
    return 1;
}

int32_t pdfium_end_page(int64_t page_handle) {
    if (!page_handle) return 0;
    std::lock_guard<std::mutex> lock(g_pdfium_mutex);
    FPDF_PAGE page = reinterpret_cast<FPDF_PAGE>(page_handle);
    const bool ok = FPDFPage_GenerateContent(page);
    if (ok && FPDFPage_GetAnnotCount(page) > 0) {
        // Rendering the annotations gives them their appearance (written with them): the readers
        // without a generator of their own show them too
        FPDF_BITMAP bitmap = FPDFBitmap_Create(1, 1, 1);
        if (bitmap) {
            FPDF_RenderPageBitmap(bitmap, page, 0, 0, 1, 1, 0, FPDF_ANNOT);
            FPDFBitmap_Destroy(bitmap);
        }
    }
    FPDF_ClosePage(page);
    return ok ? 1 : 0;
}

namespace {
struct FileWriter : FPDF_FILEWRITE {
    FILE* file;
    // Bytes written, the last of them
    unsigned long long length;
    std::string tail;
};

int write_block(FPDF_FILEWRITE* self, const void* data, unsigned long size) {
    auto* writer = static_cast<FileWriter*>(self);
    if (std::fwrite(data, 1, size, writer->file) != size) return 0;
    writer->length += size;
    writer->tail.append(static_cast<const char*>(data), size);
    const size_t kept = 4096;
    if (writer->tail.size() > 2 * kept) writer->tail.erase(0, writer->tail.size() - kept);
    return 1;
}

/** The value written after [key] in [text] from [from] (up to a delimiter) into [value], or empty. */
void value_after(const std::string& text, size_t from, const char* key, std::string& value) {
    value.clear();
    const size_t at = text.find(key, from);
    if (at == std::string::npos) return;
    size_t start = at + std::strlen(key);
    while (start < text.size() && std::isspace(static_cast<unsigned char>(text[start]))) start++;
    size_t end = start;
    while (end < text.size() && text[end] != '/' && text[end] != '>') end++;
    while (end > start && std::isspace(static_cast<unsigned char>(text[end - 1]))) end--;
    value = text.substr(start, end - start);
}

/**
 * Appends to the file of [writer] (a document just saved, its cross-reference table and trailer
 * at its end) an update adding the information dictionary [info], unless its trailer cannot be
 * read. Returns false when the update cannot be written.
 */
bool append_info(FileWriter& writer, const char* info) {
    const std::string& tail = writer.tail;
    const size_t trailer = tail.rfind("trailer");
    const size_t startxref = tail.rfind("startxref");
    if (trailer == std::string::npos || startxref == std::string::npos || startxref < trailer) return true;
    std::string root, size_text;
    value_after(tail, trailer, "/Root", root);
    value_after(tail, trailer, "/Size", size_text);
    const long long size = std::atoll(size_text.c_str());
    const long long previous = std::atoll(tail.c_str() + startxref + std::strlen("startxref"));
    if (root.empty() || size <= 0 || previous <= 0) return true;
    const size_t id_at = tail.find("/ID", trailer);
    const size_t id_end = id_at == std::string::npos ? id_at : tail.find(']', id_at);
    const std::string id = id_end == std::string::npos || id_end > startxref ? "" : tail.substr(id_at, id_end + 1 - id_at);

    std::string update = "\n";
    const unsigned long long object = writer.length + update.size();
    update += std::to_string(size) + " 0 obj\n<< " + info + " >>\nendobj\n";
    const unsigned long long xref = writer.length + update.size();
    char entry[24];
    std::snprintf(entry, sizeof(entry), "%010llu 00000 n \n", object);
    update += "xref\n" + std::to_string(size) + " 1\n" + entry;
    update += "trailer\n<< /Size " + std::to_string(size + 1) + " /Root " + root + " /Info " + std::to_string(size) +
        " 0 R /Prev " + std::to_string(previous) + (id.empty() ? "" : " " + id) + " >>\n";
    update += "startxref\n" + std::to_string(xref) + "\n%%EOF\n";
    return std::fwrite(update.data(), 1, update.size(), writer.file) == update.size();
}
}  // namespace

int32_t pdfium_save_document(int64_t doc_handle, const char* file_path, const char* info) {
    if (!doc_handle || !file_path) return 0;
    std::lock_guard<std::mutex> lock(g_pdfium_mutex);
    FileWriter writer;
    writer.version = 1;
    writer.WriteBlock = write_block;
    writer.length = 0;
    writer.file = std::fopen(file_path, "wb");
    if (!writer.file) return 0;
    bool saved = FPDF_SaveAsCopy(reinterpret_cast<FPDF_DOCUMENT>(doc_handle), &writer, FPDF_NO_INCREMENTAL);
    // PDFium writes no information dictionary: added as an update of the file
    if (saved && info && *info) saved = append_info(writer, info);
    const bool closed = std::fclose(writer.file) == 0;
    return saved && closed ? 1 : 0;
}

}
