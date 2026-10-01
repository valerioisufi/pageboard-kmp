#include "config.h"

#ifdef DRAWVIEW_TEX_CAPI

#include "graphics_capi.h"
#include "tex_capi.h"

#include "common.h"
#include "latex.h"

#include <exception>
#include <string>

using namespace tex;

extern "C" {

void tex_set_callbacks(const TexCallbacks* callbacks) {
    if (callbacks != nullptr) gTexCallbacks = *callbacks;
}

int32_t tex_init(const char* res_dir) {
    if (res_dir == nullptr) return 0;
    try {
        LaTeX::init(res_dir);
        return 1;
    } catch (...) {
        return 0;
    }
}

void tex_release(void) {
    try {
        LaTeX::release();
    } catch (...) {
    }
}

int64_t tex_parse(const char* latex, int32_t width, float text_size, float line_space, int32_t foreground) {
    if (latex == nullptr) return 0;
    try {
        std::wstring value = utf82wide(latex);
        TeXRender* render = LaTeX::parse(value, width, text_size, line_space, static_cast<color>(foreground));
        return reinterpret_cast<int64_t>(render);
    } catch (...) {
        return 0;
    }
}

void tex_render_draw(int64_t render, int64_t recorder, int32_t x, int32_t y) {
    if (render == 0) return;
    try {
        Graphics2D_CAPI g2(recorder);
        reinterpret_cast<TeXRender*>(render)->draw(g2, x, y);
    } catch (...) {
    }
}

float tex_render_get_text_size(int64_t render) {
    return render == 0 ? 0.f : reinterpret_cast<TeXRender*>(render)->getTextSize();
}

int32_t tex_render_get_height(int64_t render) {
    return render == 0 ? 0 : reinterpret_cast<TeXRender*>(render)->getHeight();
}

int32_t tex_render_get_depth(int64_t render) {
    return render == 0 ? 0 : reinterpret_cast<TeXRender*>(render)->getDepth();
}

int32_t tex_render_get_width(int64_t render) {
    return render == 0 ? 0 : reinterpret_cast<TeXRender*>(render)->getWidth();
}

float tex_render_get_baseline(int64_t render) {
    return render == 0 ? 0.f : reinterpret_cast<TeXRender*>(render)->getBaseline();
}

void tex_render_set_text_size(int64_t render, float size) {
    if (render != 0) reinterpret_cast<TeXRender*>(render)->setTextSize(size);
}

void tex_render_set_foreground(int64_t render, int32_t c) {
    if (render != 0) reinterpret_cast<TeXRender*>(render)->setForeground(static_cast<color>(c));
}

void tex_render_set_width(int64_t render, int32_t width, int32_t align) {
    if (render != 0) reinterpret_cast<TeXRender*>(render)->setWidth(width, align);
}

void tex_render_set_height(int64_t render, int32_t height, int32_t align) {
    if (render != 0) reinterpret_cast<TeXRender*>(render)->setHeight(height, align);
}

void tex_render_free(int64_t render) {
    if (render != 0) delete reinterpret_cast<TeXRender*>(render);
}

}  // extern "C"

#endif  // DRAWVIEW_TEX_CAPI
