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

#ifdef __cplusplus
}
#endif

#endif // PDFIUM_BRIDGE_H
