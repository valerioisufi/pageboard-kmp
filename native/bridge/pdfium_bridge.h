#ifndef PDFIUM_BRIDGE_H
#define PDFIUM_BRIDGE_H

#ifdef __cplusplus
extern "C" {
#endif

#include <stdint.h>

/**
 * Initializes the PDFium library.
 */
void pdfium_init(void);

/**
 * Destroys the PDFium library.
 */
void pdfium_destroy(void);

/**
 * Opens a PDF document from a file path.
 * Returns an opaque document handle, or 0 on failure.
 */
int64_t pdfium_open_document(const char* file_path, const char* password);

/**
 * Gets the number of pages in the document.
 */
int32_t pdfium_get_page_count(int64_t doc_handle);

/**
 * Gets the width and height of a page in PDF points (1/72 inch).
 * Returns 1 on success, 0 on failure.
 */
int32_t pdfium_get_page_size(int64_t doc_handle, int32_t page_index, double* out_width_pt, double* out_height_pt);

/**
 * Renders a page (or scaled region) into an ARGB/BGRA pixel buffer (32-bit int array).
 * Returns 1 on success, 0 on failure.
 */
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
);

/**
 * Text of a page (UTF-16, without the terminator) into [out_text], when [capacity] is more than
 * its length. Returns the length of the text in UTF-16 units (call it first with a null buffer to
 * size it), or -1 when the page cannot be read.
 */
int32_t pdfium_get_page_text(int64_t doc_handle, int32_t page_index, uint16_t* out_text, int32_t capacity);

/**
 * Characters of the text of a page with their boxes, in reading order: the Unicode code point of
 * every character into [out_chars] (0 when unknown) and its box into [out_boxes] (four floats:
 * left, top, right, bottom in points from the top-left corner of the page as it is displayed,
 * like the pixels of pdfium_render_page_bitmap at one pixel per point). The spaces and line
 * breaks added by PDFium have an empty box. The buffers are filled when [capacity] is at least
 * the number of characters. Returns the number of characters (call it first with null buffers
 * to size them), or -1 when the page cannot be read.
 */
int32_t pdfium_get_page_text_layout(int64_t doc_handle, int32_t page_index, int32_t* out_chars, float* out_boxes, int32_t capacity);

/**
 * Closes the document handle.
 */
void pdfium_close_document(int64_t doc_handle);

// ==========================================================
// Writing (the coordinates are in the PDF user space of the page: points from its bottom-left
// corner; a matrix is six values a, b, c, d, e, f)
// ==========================================================

/**
 * Creates a new empty document (closed by pdfium_close_document).
 * Returns its handle, or 0 on failure.
 */
int64_t pdfium_new_document(void);

/**
 * Adds a page of [width_pt] x [height_pt] at the end of the document. Returns its handle, open
 * until pdfium_end_page, or 0 on failure.
 */
int64_t pdfium_new_page(int64_t doc_handle, double width_pt, double height_pt);

/**
 * Adds a path to [page_handle]. [verbs] are 0 (move to, one point), 1 (line to, one point),
 * 2 (cubic Bezier to, three points) and 3 (close); [points] has two floats per point. The path is
 * filled with the non-zero winding rule in [fill_argb] when its alpha is not 0, and stroked
 * [stroke_width] wide in [stroke_argb] when its alpha is not 0.
 * Returns 1 on success, 0 on failure.
 */
int32_t pdfium_page_add_path(
    int64_t page_handle,
    const uint8_t* verbs,
    int32_t verb_count,
    const float* points,
    uint32_t fill_argb,
    uint32_t stroke_argb,
    float stroke_width
);

/**
 * Adds the JPEG [data] to [page_handle], embedded as it is: [matrix] maps the unit square of the
 * image (its bottom-left corner at the origin) to the page.
 * Returns 1 on success, 0 on failure.
 */
int32_t pdfium_page_add_jpeg(int64_t doc_handle, int64_t page_handle, const uint8_t* data, int32_t size, const double* matrix);

/**
 * Adds the [width] x [height] pixels [bgra] (unpremultiplied, top row first; the transparent
 * pixels stay transparent) to [page_handle]: [matrix] maps the unit square of the image (its
 * bottom-left corner at the origin) to the page.
 * Returns 1 on success, 0 on failure.
 */
int32_t pdfium_page_add_bitmap(int64_t doc_handle, int64_t page_handle, const uint32_t* bgra, int32_t width, int32_t height, const double* matrix);

/**
 * Adds page [page_index] of [source_handle] (a document opened by pdfium_open_document, whose
 * annotations are flattened into the page first) to [page_handle], keeping its content (vectors,
 * text, fonts). [matrix] maps the page as it is displayed (rotated, cropped; points from its
 * bottom-left corner) to the page.
 * Returns 1 on success, 0 on failure.
 */
int32_t pdfium_page_add_pdf_page(int64_t doc_handle, int64_t page_handle, int64_t source_handle, int32_t page_index, const double* matrix);

/**
 * Adds an annotation of [kind] (0 highlight, 1 underline, 2 strikeout) of the color [argb] over
 * [quad_count] lines: [quads] has eight floats per line, its upper-left, upper-right, lower-left
 * and lower-right corners.
 * Returns 1 on success, 0 on failure.
 */
int32_t pdfium_page_add_markup(int64_t page_handle, int32_t kind, const float* quads, int32_t quad_count, uint32_t argb);

/**
 * Adds the text [utf16] ([length] UTF-16 units, one line, characters of the WinAnsi encoding) to
 * [page_handle], invisible (searchable and selectable), in Helvetica [font_size] points, narrowed
 * to [max_width] points when it is wider: [matrix] places the start of its baseline.
 * Returns 1 on success, 0 on failure.
 */
int32_t pdfium_page_add_invisible_text(
    int64_t doc_handle,
    int64_t page_handle,
    const uint16_t* utf16,
    int32_t length,
    float font_size,
    float max_width,
    const double* matrix
);

/**
 * Writes the content of [page_handle] and closes it.
 * Returns 1 on success, 0 on failure.
 */
int32_t pdfium_end_page(int64_t page_handle);

/**
 * Saves the document to [file_path].
 * Returns 1 on success, 0 on failure.
 */
int32_t pdfium_save_document(int64_t doc_handle, const char* file_path);

#ifdef __cplusplus
}
#endif

#endif // PDFIUM_BRIDGE_H
