#ifndef DRAWVIEW_TEX_CAPI_H
#define DRAWVIEW_TEX_CAPI_H

/*
 * Plain C API of the MicroTeX engine, used by Kotlin/Native (iOS).
 *
 * Like the JNI port used on Android and on the desktop JVM, the engine does not draw by itself:
 * drawing commands are recorded through [TexCallbacks.record] and replayed by Kotlin on a Skia
 * canvas. Fonts are created and measured by Kotlin as well, identified by opaque handles.
 */

#ifdef __cplusplus
extern "C" {
#endif

#include <stdint.h>

/* Drawing commands, kept in sync with io.nano.tex.ActionRecorder */
enum {
    TEX_ACT_SET_FONT = 0,
    TEX_ACT_SET_COLOR = 1,
    TEX_ACT_SET_STROKE_WIDTH = 2,
    TEX_ACT_SET_STROKE = 3,
    TEX_ACT_TRANSLATE = 4,
    TEX_ACT_SCALE = 5,
    TEX_ACT_ROTATE = 6,
    TEX_ACT_RESET = 7,
    TEX_ACT_DRAW_CHAR = 8,
    TEX_ACT_DRAW_TEXT = 9,
    TEX_ACT_DRAW_LINE = 10,
    TEX_ACT_DRAW_RECT = 11,
    TEX_ACT_FILL_RECT = 12,
    TEX_ACT_DRAW_ROUND_RECT = 13,
    TEX_ACT_FILL_ROUND_RECT = 14
};

typedef struct {
    /* Returns a font handle (> 0) for the font file at [path], or 0 on failure. */
    int64_t (*create_font_from_file)(const char* path, float size);
    /* Returns a font handle (> 0) for the system font [name] with [style] (bit 0 bold, bit 1 italic). */
    int64_t (*create_font_from_name)(const char* name, int32_t style, float size);
    /* Returns a font handle derived from [font] with [style]. */
    int64_t (*derive_font)(int64_t font, int32_t style);
    /* Releases a font handle. */
    void (*release_font)(int64_t font);
    /*
     * Measures [text] (UTF-8) with [font] at the factored text size (10): writes
     * x, y, width, height into [out_bounds].
     */
    void (*text_bounds)(const char* text, int64_t font, float* out_bounds);
    /*
     * Records a drawing command in the recorder [recorder].
     * [font] is the font handle of TEX_ACT_SET_FONT (0 for the default font), [text] the UTF-8
     * text of TEX_ACT_DRAW_TEXT (NULL otherwise).
     */
    void (*record)(int64_t recorder, int32_t action, int64_t font, const char* text, const float* args, int32_t args_count);
} TexCallbacks;

/* Registers the callbacks: must be called before tex_init. */
void tex_set_callbacks(const TexCallbacks* callbacks);

/* Initializes the engine with the resources directory. Returns 1 on success. */
int32_t tex_init(const char* res_dir);

/* Releases the engine. */
void tex_release(void);

/* Parses a formula (UTF-8). Returns a render handle, or 0 on failure. */
int64_t tex_parse(const char* latex, int32_t width, float text_size, float line_space, int32_t foreground);

/* Records the drawing commands of [render] at ([x], [y]) into [recorder]. */
void tex_render_draw(int64_t render, int64_t recorder, int32_t x, int32_t y);

float tex_render_get_text_size(int64_t render);
int32_t tex_render_get_height(int64_t render);
int32_t tex_render_get_depth(int64_t render);
int32_t tex_render_get_width(int64_t render);
float tex_render_get_baseline(int64_t render);
void tex_render_set_text_size(int64_t render, float size);
void tex_render_set_foreground(int64_t render, int32_t color);
void tex_render_set_width(int64_t render, int32_t width, int32_t align);
void tex_render_set_height(int64_t render, int32_t height, int32_t align);
void tex_render_free(int64_t render);

#ifdef __cplusplus
}
#endif

#endif /* DRAWVIEW_TEX_CAPI_H */
