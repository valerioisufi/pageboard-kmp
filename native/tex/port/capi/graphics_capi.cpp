#include "config.h"

#ifdef PAGEBOARD_TEX_CAPI

#include "graphics_capi.h"

#include "common.h"

#include <cstring>

using namespace tex;
using namespace std;

TexCallbacks gTexCallbacks = {};

/******************************************* Font *************************************************/

TexFontHandle::~TexFontHandle() {
    if (value != 0 && gTexCallbacks.release_font != nullptr) {
        gTexCallbacks.release_font(value);
    }
}

Font_CAPI::Font_CAPI(int64_t handle, float size)
    : _size(size), _handle(handle != 0 ? make_shared<TexFontHandle>(handle) : nullptr) {}

int64_t Font_CAPI::handle() const {
    return _handle == nullptr ? 0 : _handle->value;
}

float Font_CAPI::getSize() const {
    return _size;
}

shared_ptr<Font> Font_CAPI::deriveFont(int style) const {
    int64_t derived = 0;
    if (gTexCallbacks.derive_font != nullptr && handle() != 0) {
        derived = gTexCallbacks.derive_font(handle(), style);
    }
    if (derived == handle()) {
        auto f = new Font_CAPI(0, _size);
        f->_handle = _handle;
        return shared_ptr<Font>(f);
    }
    return shared_ptr<Font>(new Font_CAPI(derived, _size));
}

bool Font_CAPI::operator==(const Font& f) const {
    return handle() == static_cast<const Font_CAPI&>(f).handle();
}

bool Font_CAPI::operator!=(const Font& f) const {
    return !(*this == f);
}

Font* Font::create(const string& file, float size) {
    int64_t handle = 0;
    if (gTexCallbacks.create_font_from_file != nullptr) {
        handle = gTexCallbacks.create_font_from_file(file.c_str(), size);
    }
    return new Font_CAPI(handle, size);
}

shared_ptr<Font> Font::_create(const string& name, int style, float size) {
    int64_t handle = 0;
    if (gTexCallbacks.create_font_from_name != nullptr) {
        handle = gTexCallbacks.create_font_from_name(name.c_str(), style, size);
    }
    return shared_ptr<Font>(new Font_CAPI(handle, size));
}

/******************************************* Text layout ******************************************/

void TextLayout_CAPI::getBounds(_out_ Rect& bounds) {
    bounds.x = bounds.y = bounds.w = bounds.h = 0;
    if (gTexCallbacks.text_bounds == nullptr || _font == nullptr) return;
    float out[4] = {0, 0, 0, 0};
    string str = wide2utf8(_txt.c_str());
    gTexCallbacks.text_bounds(str.c_str(), static_cast<Font_CAPI*>(_font.get())->handle(), out);
    bounds.x = out[0];
    bounds.y = out[1];
    bounds.w = out[2];
    bounds.h = out[3];
}

void TextLayout_CAPI::draw(Graphics2D& g2, float x, float y) {
    const Font* oldFont = g2.getFont();
    g2.setFont(_font.get());
    g2.drawText(_txt, x, y);
    g2.setFont(oldFont);
}

shared_ptr<TextLayout> TextLayout::create(const wstring& txt, const shared_ptr<Font>& font) {
    return shared_ptr<TextLayout>(new TextLayout_CAPI(txt, font));
}

/******************************************* Graphics 2D ******************************************/

enum AffineTransformIndex {
    SX,
    SY,
    TX,
    TY,
    R,
    PX,
    PY
};

Graphics2D_CAPI::Graphics2D_CAPI(int64_t recorder) : _recorder(recorder), _font(nullptr), _stroke() {
    memset(_t, 0, sizeof(_t));
    _t[SX] = _t[SY] = 1;
    _color = black;
}

void Graphics2D_CAPI::record(int action, int64_t font, const char* text, const float* args, int count) {
    if (gTexCallbacks.record != nullptr) {
        gTexCallbacks.record(_recorder, action, font, text, args, count);
    }
}

void Graphics2D_CAPI::setColor(color c) {
    _color = c;
    float p[] = {static_cast<float>(static_cast<int32_t>(c))};
    record(TEX_ACT_SET_COLOR, 0, nullptr, p, 1);
}

color Graphics2D_CAPI::getColor() const {
    return _color;
}

void Graphics2D_CAPI::setStroke(const Stroke& s) {
    _stroke = s;
    float p[] = {s.lineWidth, s.miterLimit, static_cast<float>(s.cap), static_cast<float>(s.join)};
    record(TEX_ACT_SET_STROKE, 0, nullptr, p, 4);
}

const Stroke& Graphics2D_CAPI::getStroke() const {
    return _stroke;
}

void Graphics2D_CAPI::setStrokeWidth(float w) {
    _stroke.lineWidth = w;
    float p[] = {w};
    record(TEX_ACT_SET_STROKE_WIDTH, 0, nullptr, p, 1);
}

const Font* Graphics2D_CAPI::getFont() const {
    return _font;
}

void Graphics2D_CAPI::setFont(const Font* font) {
    _font = font;
    int64_t handle = font == nullptr ? 0 : static_cast<const Font_CAPI*>(font)->handle();
    record(TEX_ACT_SET_FONT, handle, nullptr, nullptr, 0);
}

void Graphics2D_CAPI::translate(float dx, float dy) {
    _t[TX] += _t[SX] * dx;
    _t[TY] += _t[SY] * dy;
    float p[] = {dx, dy};
    record(TEX_ACT_TRANSLATE, 0, nullptr, p, 2);
}

void Graphics2D_CAPI::scale(float sx, float sy) {
    _t[SX] *= sx;
    _t[SY] *= sy;
    float p[] = {sx, sy};
    record(TEX_ACT_SCALE, 0, nullptr, p, 2);
}

void Graphics2D_CAPI::rotate(float angle) {
    rotate(angle, 0, 0);
}

void Graphics2D_CAPI::rotate(float angle, float px, float py) {
    _t[R] += static_cast<float>(angle / PI * 180);
    _t[PX] = px * _t[SX] + _t[TX];
    _t[PY] = py * _t[SY] + _t[TY];
    float p[] = {angle, px, py};
    record(TEX_ACT_ROTATE, 0, nullptr, p, 3);
}

void Graphics2D_CAPI::reset() {
    memset(_t, 0, sizeof(_t));
    _t[SX] = _t[SY] = 1;
    record(TEX_ACT_RESET, 0, nullptr, nullptr, 0);
}

float Graphics2D_CAPI::sx() const {
    return _t[SX];
}

float Graphics2D_CAPI::sy() const {
    return _t[SY];
}

void Graphics2D_CAPI::drawChar(wchar_t c, float x, float y) {
    float p[] = {static_cast<float>(c), x, y};
    record(TEX_ACT_DRAW_CHAR, 0, nullptr, p, 3);
}

void Graphics2D_CAPI::drawText(const wstring& c, float x, float y) {
    string str = wide2utf8(c.c_str());
    float p[] = {x, y};
    record(TEX_ACT_DRAW_TEXT, 0, str.c_str(), p, 2);
}

void Graphics2D_CAPI::drawLine(float x1, float y1, float x2, float y2) {
    float p[] = {x1, y1, x2, y2};
    record(TEX_ACT_DRAW_LINE, 0, nullptr, p, 4);
}

void Graphics2D_CAPI::drawRect(float x, float y, float w, float h) {
    float p[] = {x, y, w, h};
    record(TEX_ACT_DRAW_RECT, 0, nullptr, p, 4);
}

void Graphics2D_CAPI::fillRect(float x, float y, float w, float h) {
    float p[] = {x, y, w, h};
    record(TEX_ACT_FILL_RECT, 0, nullptr, p, 4);
}

void Graphics2D_CAPI::drawRoundRect(float x, float y, float w, float h, float rx, float ry) {
    float p[] = {x, y, w, h, rx, ry};
    record(TEX_ACT_DRAW_ROUND_RECT, 0, nullptr, p, 6);
}

void Graphics2D_CAPI::fillRoundRect(float x, float y, float w, float h, float rx, float ry) {
    float p[] = {x, y, w, h, rx, ry};
    record(TEX_ACT_FILL_ROUND_RECT, 0, nullptr, p, 6);
}

#endif  // PAGEBOARD_TEX_CAPI
