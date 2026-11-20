// The handwriting recognizers of Windows (Windows.UI.Input.Inking.InkRecognizerContainer) for the
// desktop JVM: the names of the recognizers installed with the languages of Windows, and the
// candidates of each word of a set of strokes. Called by WindowsInkRecognizer
// (pageboard-handwriting-windows) from one thread of its own, which this bridge makes a
// single-threaded apartment (the ink of Windows reads nothing in a multithreaded one).
#include <jni.h>

#include <windows.h>

#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.Foundation.Numerics.h>
#include <winrt/Windows.UI.Input.Inking.h>

#include <string>
#include <vector>

using namespace winrt;
using namespace winrt::Windows::Foundation;
using namespace winrt::Windows::UI::Input::Inking;

namespace {

// Waits for [operation] on this single-threaded apartment, its messages dispatched meanwhile (a
// blocking get() would never see the operation complete), at most [timeoutMs].
template <typename Operation>
bool waitPumping(Operation const& operation, DWORD timeoutMs) {
    HANDLE done = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    if (done == nullptr) return false;
    operation.Completed([done](auto&&, auto&&) { SetEvent(done); });
    ULONGLONG deadline = GetTickCount64() + timeoutMs;
    bool completed = false;
    for (;;) {
        ULONGLONG now = GetTickCount64();
        if (now >= deadline) break;
        DWORD result = MsgWaitForMultipleObjects(1, &done, FALSE, static_cast<DWORD>(deadline - now), QS_ALLINPUT);
        if (result == WAIT_OBJECT_0) {
            completed = true;
            break;
        }
        if (result != WAIT_OBJECT_0 + 1) break;
        MSG message;
        while (PeekMessageW(&message, nullptr, 0, 0, PM_REMOVE)) {
            TranslateMessage(&message);
            DispatchMessageW(&message);
        }
    }
    CloseHandle(done);
    return completed;
}

bool ensureApartment() {
    thread_local bool initialized = false;
    if (initialized) return true;
    try {
        init_apartment(apartment_type::single_threaded);
    } catch (hresult_error const&) {
        // Already initialized by someone else on this thread: use it as it is
    }
    initialized = true;
    return true;
}

jstring toJava(JNIEnv* env, hstring const& text) {
    return env->NewString(reinterpret_cast<jchar const*>(text.c_str()), static_cast<jsize>(text.size()));
}

hstring fromJava(JNIEnv* env, jstring text) {
    jchar const* chars = env->GetStringChars(text, nullptr);
    hstring result(reinterpret_cast<wchar_t const*>(chars), static_cast<uint32_t>(env->GetStringLength(text)));
    env->ReleaseStringChars(text, chars);
    return result;
}

jobjectArray strings(JNIEnv* env, std::vector<hstring> const& values) {
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray array = env->NewObjectArray(static_cast<jsize>(values.size()), stringClass, nullptr);
    for (size_t i = 0; i < values.size(); i++) {
        jstring value = toJava(env, values[i]);
        env->SetObjectArrayElement(array, static_cast<jsize>(i), value);
        env->DeleteLocalRef(value);
    }
    return array;
}

} // namespace

extern "C" {

// The names of the recognizers installed, or null when Windows has none.
JNIEXPORT jobjectArray JNICALL
Java_io_github_valerioisufi_pageboard_handwriting_windows_WindowsInkBridge_recognizers(JNIEnv* env, jobject) {
    try {
        ensureApartment();
        InkRecognizerContainer container;
        std::vector<hstring> names;
        for (auto const& recognizer : container.GetRecognizers()) names.push_back(recognizer.Name());
        return strings(env, names);
    } catch (...) {
        return nullptr;
    }
}

// The words read in the strokes, each as its candidates joined by '\n' (the likelier first), with
// the recognizer named [name]. The strokes: [xy] the points (x, y in device-independent pixels),
// [sizes] the number of points of each stroke, [times] the time of each point (microseconds).
// Null when it cannot read.
JNIEXPORT jobjectArray JNICALL
Java_io_github_valerioisufi_pageboard_handwriting_windows_WindowsInkBridge_recognize(
    JNIEnv* env, jobject, jstring name, jfloatArray xy, jintArray sizes, jlongArray times) {
    try {
        ensureApartment();
        hstring wanted = fromJava(env, name);
        InkRecognizerContainer container;
        bool found = false;
        for (auto const& recognizer : container.GetRecognizers()) {
            if (recognizer.Name() == wanted) {
                container.SetDefaultRecognizer(recognizer);
                found = true;
                break;
            }
        }
        if (!found) return nullptr;

        jsize strokeCount = env->GetArrayLength(sizes);
        std::vector<jint> counts(strokeCount);
        env->GetIntArrayRegion(sizes, 0, strokeCount, counts.data());
        jsize pointCount = env->GetArrayLength(times);
        std::vector<jfloat> points(static_cast<size_t>(pointCount) * 2);
        std::vector<jlong> stamps(pointCount);
        env->GetFloatArrayRegion(xy, 0, pointCount * 2, points.data());
        env->GetLongArrayRegion(times, 0, pointCount, stamps.data());

        InkStrokeContainer strokes;
        InkStrokeBuilder builder;
        size_t next = 0;
        for (jint count : counts) {
            std::vector<InkPoint> stroke;
            for (jint k = 0; k < count && next < static_cast<size_t>(pointCount); k++, next++) {
                stroke.emplace_back(Point(points[next * 2], points[next * 2 + 1]), 0.5f, 0.0f, 0.0f, static_cast<uint64_t>(stamps[next]));
            }
            if (!stroke.empty()) {
                strokes.AddStroke(builder.CreateStrokeFromInkPoints(stroke, winrt::Windows::Foundation::Numerics::float3x2::identity(), nullptr, nullptr));
            }
        }
        auto operation = container.RecognizeAsync(strokes, InkRecognitionTarget::All);
        if (!waitPumping(operation, 10000)) {
            operation.Cancel();
            return nullptr;
        }
        auto results = operation.GetResults();
        std::vector<hstring> words;
        for (auto const& result : results) {
            std::wstring candidates;
            for (auto const& candidate : result.GetTextCandidates()) {
                if (!candidates.empty()) candidates += L'\n';
                candidates += candidate.c_str();
            }
            words.emplace_back(candidates);
        }
        return strings(env, words);
    } catch (...) {
        return nullptr;
    }
}

} // extern "C"
