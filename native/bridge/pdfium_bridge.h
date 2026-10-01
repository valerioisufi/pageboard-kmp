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
 * Closes the document handle.
 */
void pdfium_close_document(int64_t doc_handle);

#ifdef __cplusplus
}
#endif

#endif // PDFIUM_BRIDGE_H
