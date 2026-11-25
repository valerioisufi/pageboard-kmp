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
 *
 * The last pages read (rendered, their text read) stay loaded, their images decoded with them,
 * until other pages are read, their document changes or is closed: rendering the same page again
 * (another region of it) neither parses it nor decodes its images again.
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
 * Closes the document handle, and its pages kept loaded.
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
 * Adds a page of [width_pt] x [height_pt] at [index] of the document (before the pages imported
 * and not yet written, see pdfium_import_pages). Returns its handle, open until pdfium_end_page,
 * or 0 on failure.
 */
int64_t pdfium_new_page(int64_t doc_handle, int32_t index, double width_pt, double height_pt);

/**
 * Imports the pages [page_indices] ([count], distinct) of [source_handle] (a document opened by
 * pdfium_open_document) at the end of the document in one go, so that the fonts and the images
 * they share are written once. Their annotations shown are flattened into them first (as
 * pdfium_page_add_pdf_page does), the others left out, but their links to the web, which stay
 * links. Each is then written over after pdfium_place_page.
 * Returns 1 on success, 0 on failure.
 */
int32_t pdfium_import_pages(int64_t doc_handle, int64_t source_handle, const int32_t* page_indices, int32_t count);

/**
 * Moves page [from_index] of the document (imported by pdfium_import_pages) to [to_index] and
 * opens it to add content over its own, which stays as it is (what is added starts from the
 * default graphics state). [out_matrix] receives the matrix from the points of the page as it is
 * displayed (from its top-left corner, y going down) to its user space.
 * Returns its handle, open until pdfium_end_page, or 0 on failure.
 */
int64_t pdfium_place_page(int64_t doc_handle, int32_t from_index, int32_t to_index, double* out_matrix);

/**
 * Removes page [index] of the document.
 * Returns 1 on success, 0 on failure.
 */
int32_t pdfium_delete_page(int64_t doc_handle, int32_t index);

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
 * and lower-right corners. [appearance] is the content stream drawing it (in the user space of
 * the page; its opacity, when the color has one, is the graphics state /GS), or null for the one
 * PDFium generates.
 * Returns 1 on success, 0 on failure.
 */
int32_t pdfium_page_add_markup(int64_t page_handle, int32_t kind, const float* quads, int32_t quad_count, uint32_t argb, const char* appearance);

/**
 * Adds a link annotation opening [uri] over [quad_count] rectangles: [quads] has eight floats
 * per rectangle, its upper-left, upper-right, lower-left and lower-right corners in user space.
 * Returns 1 on success, 0 on failure.
 */
int32_t pdfium_page_add_link(int64_t page_handle, const float* quads, int32_t quad_count, const char* uri);

/**
 * Loads the TrueType font [data] into the document (embedded, its characters mapped to Unicode).
 * Returns its handle, closed by pdfium_close_font, or 0 on failure.
 */
int64_t pdfium_load_font(int64_t doc_handle, const uint8_t* data, int32_t size);

/**
 * Closes a font handle of pdfium_load_font (the font stays in the document).
 */
void pdfium_close_font(int64_t font_handle);

/**
 * Adds the text [utf16] ([length] UTF-16 units, one line) to [page_handle] in [font_handle],
 * [font_size] units high, filled with [argb] or invisible (searchable and selectable): [matrix]
 * places the start of its baseline.
 * Returns 1 on success, 0 on failure.
 */
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
);

/**
 * Writes the content of [page_handle] and closes it.
 * Returns 1 on success, 0 on failure.
 */
int32_t pdfium_end_page(int64_t page_handle);

/**
 * Saves the document to [file_path], with [info] (the entries of its information dictionary in
 * the syntax of PDF, such as "/Title (Notes)", or null) written as an update of the file.
 * Returns 1 on success, 0 on failure.
 */
int32_t pdfium_save_document(int64_t doc_handle, const char* file_path, const char* info);

#ifdef __cplusplus
}
#endif

#endif // PDFIUM_BRIDGE_H
