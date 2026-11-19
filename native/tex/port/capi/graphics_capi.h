#include "config.h"

#ifdef PAGEBOARD_TEX_CAPI

#ifndef GRAPHICS_CAPI_INCLUDED
#define GRAPHICS_CAPI_INCLUDED

#include "graphic/graphic.h"
#include "tex_capi.h"

#include <memory>

using namespace tex;

/** Callbacks registered by Kotlin/Native. */
extern TexCallbacks gTexCallbacks;

/** Owns a Kotlin font handle and releases it when the last reference goes away. */
struct TexFontHandle {
    int64_t value;

    explicit TexFontHandle(int64_t v) : value(v) {}
    ~TexFontHandle();

    TexFontHandle(const TexFontHandle&) = delete;
    TexFontHandle& operator=(const TexFontHandle&) = delete;
};

class Font_CAPI : public Font {
private:
    float _size;
    std::shared_ptr<TexFontHandle> _handle;

public:
    Font_CAPI(int64_t handle, float size);

    int64_t handle() const;

    virtual float getSize() const override;
    virtual std::shared_ptr<Font> deriveFont(int style) const override;
    virtual bool operator==(const Font& f) const override;
    virtual bool operator!=(const Font& f) const override;
};

class TextLayout_CAPI : public TextLayout {
private:
    std::shared_ptr<Font> _font;
    const std::wstring _txt;

public:
    TextLayout_CAPI(const std::wstring& txt, const std::shared_ptr<Font> font)
        : _font(font), _txt(txt) {}

    virtual void getBounds(_out_ Rect& bounds) override;
    virtual void draw(Graphics2D& g2, float x, float y) override;
};

class Graphics2D_CAPI : public Graphics2D {
private:
    int64_t _recorder;
    float _t[9];
    color _color;
    const Font* _font;
    Stroke _stroke;

    void record(int action, int64_t font, const char* text, const float* args, int count);

public:
    explicit Graphics2D_CAPI(int64_t recorder);

    Graphics2D_CAPI(const Graphics2D_CAPI&) = delete;
    Graphics2D_CAPI& operator=(const Graphics2D_CAPI&) = delete;

    virtual void setColor(color c) override;
    virtual color getColor() const override;
    virtual void setStroke(const Stroke& s) override;
    virtual const Stroke& getStroke() const override;
    virtual void setStrokeWidth(float w) override;
    virtual const Font* getFont() const override;
    virtual void setFont(const Font* font) override;
    virtual void translate(float dx, float dy) override;
    virtual void scale(float sx, float sy) override;
    virtual void rotate(float angle) override;
    virtual void rotate(float angle, float px, float py) override;
    virtual void reset() override;
    virtual float sx() const override;
    virtual float sy() const override;
    virtual void drawChar(wchar_t c, float x, float y) override;
    virtual void drawText(const std::wstring& c, float x, float y) override;
    virtual void drawLine(float x1, float y1, float x2, float y2) override;
    virtual void drawRect(float x, float y, float w, float h) override;
    virtual void fillRect(float x, float y, float w, float h) override;
    virtual void drawRoundRect(float x, float y, float w, float h, float rx, float ry) override;
    virtual void fillRoundRect(float x, float y, float w, float h, float rx, float ry) override;
};

#endif  // GRAPHICS_CAPI_INCLUDED
#endif  // PAGEBOARD_TEX_CAPI
