#ifndef JNI_DEF_INCLUDED
#define JNI_DEF_INCLUDED

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

/** io/github/valerioisufi/pageboard/latex/microtex/Rect */
extern jfieldID gFieldRectX, gFieldRectY, gFieldRectW, gFieldRectH;

/** io/github/valerioisufi/pageboard/latex/microtex/TextLayout */
extern jclass gClassTextLayout;
extern jmethodID gMethodGetBounds;

/** io/github/valerioisufi/pageboard/latex/microtex/Font */
extern jclass gClassFont;
extern jmethodID gMethodDeriveFont;
extern jmethodID gMethodCreateFontFromName;
extern jmethodID gMethodCreateFontFromFile;

/** io/github/valerioisufi/pageboard/latex/microtex/ActionRecorder */
extern jmethodID gMethodRecord;

#ifdef __cplusplus
}
#endif

/** Get the jni environment */
JNIEnv* getJNIEnv();

#endif  // JNI_DEF_INCLUDED
