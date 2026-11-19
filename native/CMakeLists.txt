cmake_minimum_required(VERSION 3.16)
project(pageboard_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ==============================================================================
# Target platform
# ==============================================================================
# - Android (NDK, driven by the :pageboard-native-<engine> Gradle modules, one target each): JNI
#   shared libraries.
# - Desktop JVM (macOS / Linux / Windows with clang-cl, driven by scripts/build_desktop.sh): JNI
#   shared libraries bundled as jvmMain resources.
# - iOS (driven by scripts/build_ios.sh): static libraries exposing the plain C API,
#   consumed by Kotlin/Native through cinterop.
if(CMAKE_SYSTEM_NAME STREQUAL "iOS")
    set(PAGEBOARD_IOS ON)
else()
    set(PAGEBOARD_IOS OFF)
endif()

if(PAGEBOARD_IOS)
    set(PAGEBOARD_DEFAULT_JNI OFF)
else()
    set(PAGEBOARD_DEFAULT_JNI ON)
endif()
option(PAGEBOARD_BUILD_JNI "Build the JNI shared libraries (Android / desktop JVM)" ${PAGEBOARD_DEFAULT_JNI})

# The Ink stroke modeller and the TeX layout engine run on the UI thread: an unoptimised
# build (Android debug variants are configured with CMAKE_BUILD_TYPE=Debug) is unusable.
if(NOT MSVC)
    add_compile_options(-O2)
endif()
add_compile_definitions(NDEBUG)

# Windows (clang-cl): static C/C++ runtime, so that the JNI libraries do not depend on the
# Visual C++ redistributable; M_PI & co. from <cmath>; no min/max macros from <windows.h>.
if(MSVC)
    set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()
if(WIN32)
    add_compile_definitions(_USE_MATH_DEFINES NOMINMAX)
endif()

# ==============================================================================
# Abseil
# ==============================================================================
set(ABSL_PROPAGATE_CXX_STD ON CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(ABSL_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
set(ABSL_MSVC_STATIC_RUNTIME ON CACHE BOOL "" FORCE)
add_subdirectory(external/abseil-cpp EXCLUDE_FROM_ALL)

# ==============================================================================
# libtess2 (static)
# ==============================================================================
set(LIBTESS2_SRCS
    external/libtess2/Source/bucketalloc.c
    external/libtess2/Source/dict.c
    external/libtess2/Source/geom.c
    external/libtess2/Source/mesh.c
    external/libtess2/Source/priorityq.c
    external/libtess2/Source/sweep.c
    external/libtess2/Source/tess.c
)

add_library(libtess2 STATIC ${LIBTESS2_SRCS})
target_include_directories(libtess2 PUBLIC
    external/libtess2/Include
    external/include
)

# ==============================================================================
# Google Ink core (static)
# ==============================================================================
file(GLOB_RECURSE INK_SRCS
    ink/ink/brush/*.cc
    ink/ink/color/*.cc
    ink/ink/geometry/*.cc
    ink/ink/strokes/*.cc
    ink/ink/types/*.cc
)

# Filter out tests, benchmarks, fuzz domains, matchers, and the Android-specific JNI files
list(FILTER INK_SRCS EXCLUDE REGEX ".*_test.*\\.cc$")
list(FILTER INK_SRCS EXCLUDE REGEX ".*_benchmark.*\\.cc$")
list(FILTER INK_SRCS EXCLUDE REGEX ".*fuzz.*\\.cc$")
list(FILTER INK_SRCS EXCLUDE REGEX ".*matchers.*\\.cc$")
list(FILTER INK_SRCS EXCLUDE REGEX ".*recorded_test_inputs\\.cc$")
list(FILTER INK_SRCS EXCLUDE REGEX ".*synthetic_test_inputs\\.cc$")
list(FILTER INK_SRCS EXCLUDE REGEX ".*mesh_test_helpers\\.cc$")
list(FILTER INK_SRCS EXCLUDE REGEX ".*/internal/jni/.*")

add_library(ink_core STATIC ${INK_SRCS})

target_include_directories(ink_core PUBLIC
    ink
    external/include
    external/libtess2/Include
)

target_link_libraries(ink_core PUBLIC
    libtess2
    absl::base
    absl::core_headers
    absl::algorithm_container
    absl::inlined_vector
    absl::flat_hash_set
    absl::log
    absl::check
    absl::status
    absl::statusor
    absl::status_builder
    absl::synchronization
    absl::strings
    absl::str_format
    absl::span
)

# ==============================================================================
# JNI headers (desktop JVM only: the NDK sysroot already provides jni.h)
# ==============================================================================
if(PAGEBOARD_BUILD_JNI AND NOT ANDROID)
    # JAVA_HOME is set by the Gradle task buildDesktopNativeLibs to the JDK running Gradle
    if(DEFINED ENV{JAVA_HOME})
        file(TO_CMAKE_PATH "$ENV{JAVA_HOME}" PAGEBOARD_JAVA_HOME)
        if(NOT EXISTS "${PAGEBOARD_JAVA_HOME}/include")
            unset(PAGEBOARD_JAVA_HOME)
        endif()
    endif()

    if(PAGEBOARD_JAVA_HOME)
        message(STATUS "Using JNI headers from: ${PAGEBOARD_JAVA_HOME}")
        set(PAGEBOARD_JNI_INCLUDE_DIRS
            "${PAGEBOARD_JAVA_HOME}/include"
            "${PAGEBOARD_JAVA_HOME}/include/darwin"
            "${PAGEBOARD_JAVA_HOME}/include/linux"
            "${PAGEBOARD_JAVA_HOME}/include/win32"
        )
    else()
        find_package(JNI REQUIRED)
        set(PAGEBOARD_JNI_INCLUDE_DIRS ${JNI_INCLUDE_DIRS})
    endif()
endif()

function(pageboard_configure_shared_library target)
    if(ANDROID)
        target_link_libraries(${target} PRIVATE log)
        # 16 KB page size support (required by Android 15+ devices).
        target_link_options(${target} PRIVATE "-Wl,-z,max-page-size=16384")
    elseif(PAGEBOARD_JNI_INCLUDE_DIRS)
        target_include_directories(${target} PRIVATE ${PAGEBOARD_JNI_INCLUDE_DIRS})
    endif()
    if(APPLE)
        set_target_properties(${target} PROPERTIES
            MACOSX_RPATH ON
            INSTALL_NAME_DIR "@rpath"
        )
    endif()
endfunction()

# ==============================================================================
# Ink bridge
# ==============================================================================
if(PAGEBOARD_BUILD_JNI)
    add_library(ink_bridge SHARED
        bridge/ink_bridge.cpp
        bridge/ink_bridge_jni.cpp
    )
    pageboard_configure_shared_library(ink_bridge)
else()
    add_library(ink_bridge STATIC
        bridge/ink_bridge.cpp
    )
endif()

target_include_directories(ink_bridge PUBLIC
    bridge
    ink
)
target_link_libraries(ink_bridge PRIVATE ink_core)

# ==============================================================================
# Trackpad bridge (desktop JVM on macOS): phases of the scroll and pinch of the trackpad
# ==============================================================================
if(PAGEBOARD_BUILD_JNI AND APPLE AND NOT ANDROID)
    enable_language(OBJC)
    add_library(trackpad_bridge SHARED
        bridge/trackpad_bridge_mac.m
    )
    target_compile_options(trackpad_bridge PRIVATE -fobjc-arc)
    target_link_libraries(trackpad_bridge PRIVATE "-framework AppKit")
    pageboard_configure_shared_library(trackpad_bridge)

    # Audio files of other applications (import) and AAC (export) for the lecture assistant
    add_library(audio_codec_bridge SHARED
        bridge/audio_codec_mac.c
    )
    target_link_libraries(audio_codec_bridge PRIVATE "-framework AudioToolbox" "-framework CoreFoundation")
    pageboard_configure_shared_library(audio_codec_bridge)
endif()

# ==============================================================================
# MicroTeX (LaTeX math engine)
# ==============================================================================
file(GLOB_RECURSE TEX_CORE_SRCS
    tex/src/core/*.cpp
    tex/src/atom/*.cpp
    tex/src/fonts/*.cpp
    tex/src/res/*.cpp
    tex/src/xml/*.cpp
)
list(APPEND TEX_CORE_SRCS
    tex/src/latex.cpp
    tex/src/render.cpp
)

if(PAGEBOARD_BUILD_JNI)
    # The "Android" port is a JNI port: it records the drawing commands into
    # io.nano.tex.ActionRecorder, which replays them on the platform canvas
    # (android.graphics.Canvas on Android, Skia on the desktop JVM).
    add_library(tex SHARED
        ${TEX_CORE_SRCS}
        tex/port/graphics_android.cpp
        tex/port/jni_def.cpp
        tex/port/jni_help.cpp
        tex/port/jni_latex.cpp
        tex/port/jni_render.cpp
    )
    target_compile_definitions(tex PRIVATE __OS_Android__)
    pageboard_configure_shared_library(tex)
else()
    # Plain C port (iOS): drawing commands are forwarded to callbacks registered by Kotlin/Native.
    add_library(tex STATIC
        ${TEX_CORE_SRCS}
        tex/port/capi/graphics_capi.cpp
        tex/port/capi/tex_capi.cpp
    )
    target_compile_definitions(tex PRIVATE PAGEBOARD_TEX_CAPI)
    target_include_directories(tex PUBLIC tex/port/capi)
endif()

# MicroTeX relies on APIs removed in C++17 (std::ptr_fun, dynamic exception specifications).
set_target_properties(tex PROPERTIES
    CXX_STANDARD 14
    CXX_STANDARD_REQUIRED ON
    CXX_EXTENSIONS OFF
)

if(CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
    target_compile_options(tex PRIVATE -w)
elseif(MSVC)
    target_compile_options(tex PRIVATE /W0)
endif()

target_include_directories(tex PUBLIC
    tex/src
    tex/port
)

# ==============================================================================
# PDFium bridge (prebuilt PDFium from pdfium-binaries, every platform but the web)
# ==============================================================================
# PAGEBOARD_PDFIUM_DIR: an extracted pdfium-binaries package (build/pdfium/<package>, prepared
# by the Gradle tasks extractPdfium-<package>). On Android it defaults to the package of the
# ABI being built, under PAGEBOARD_PDFIUM_ROOT.
set(PAGEBOARD_PDFIUM_DIR "" CACHE PATH "Extracted pdfium-binaries package")
set(PAGEBOARD_PDFIUM_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../build/pdfium" CACHE PATH "Folder of the extracted pdfium-binaries packages")
file(TO_CMAKE_PATH "${PAGEBOARD_PDFIUM_DIR}" PAGEBOARD_PDFIUM_DIR) # Windows paths from Gradle
if(ANDROID AND NOT PAGEBOARD_PDFIUM_DIR)
    set(PAGEBOARD_PDFIUM_ANDROID_PACKAGES_arm64-v8a android-arm64)
    set(PAGEBOARD_PDFIUM_ANDROID_PACKAGES_armeabi-v7a android-arm)
    set(PAGEBOARD_PDFIUM_ANDROID_PACKAGES_x86_64 android-x64)
    set(PAGEBOARD_PDFIUM_DIR "${PAGEBOARD_PDFIUM_ROOT}/${PAGEBOARD_PDFIUM_ANDROID_PACKAGES_${ANDROID_ABI}}")
endif()

if(EXISTS "${PAGEBOARD_PDFIUM_DIR}/include/fpdfview.h")
    if(PAGEBOARD_BUILD_JNI)
        # Imported by hand: PDFiumConfig.cmake uses find_library, which the Android toolchain
        # restricts to the NDK sysroot.
        add_library(pdfium SHARED IMPORTED)
        if(WIN32)
            set_target_properties(pdfium PROPERTIES
                IMPORTED_LOCATION "${PAGEBOARD_PDFIUM_DIR}/bin/pdfium.dll"
                IMPORTED_IMPLIB "${PAGEBOARD_PDFIUM_DIR}/lib/pdfium.dll.lib"
            )
        elseif(APPLE)
            set_target_properties(pdfium PROPERTIES IMPORTED_LOCATION "${PAGEBOARD_PDFIUM_DIR}/lib/libpdfium.dylib")
        else()
            set_target_properties(pdfium PROPERTIES IMPORTED_LOCATION "${PAGEBOARD_PDFIUM_DIR}/lib/libpdfium.so")
        endif()
        set_target_properties(pdfium PROPERTIES INTERFACE_INCLUDE_DIRECTORIES "${PAGEBOARD_PDFIUM_DIR}/include")

        # JNI shared library (desktop JVM / Android); libpdfium is shipped next to it
        add_library(pdfium_bridge SHARED
            bridge/pdfium_bridge.cpp
            bridge/pdfium_bridge_jni.cpp
        )
        target_link_libraries(pdfium_bridge PRIVATE pdfium)
        pageboard_configure_shared_library(pdfium_bridge)
        # No absolute build path in the rpath
        set_target_properties(pdfium_bridge PROPERTIES BUILD_WITH_INSTALL_RPATH ON)
        if(APPLE)
            set_target_properties(pdfium_bridge PROPERTIES INSTALL_RPATH "@loader_path")
        elseif(UNIX AND NOT ANDROID)
            set_target_properties(pdfium_bridge PROPERTIES INSTALL_RPATH "$ORIGIN")
        endif()
    else()
        # Plain C API (iOS): PDFium itself is linked by the app as PDFium.xcframework
        add_library(pdfium_bridge STATIC
            bridge/pdfium_bridge.cpp
        )
        target_include_directories(pdfium_bridge PRIVATE "${PAGEBOARD_PDFIUM_DIR}/include")
    endif()
    target_include_directories(pdfium_bridge PUBLIC bridge)
endif()

# ==============================================================================
# whisper.cpp bridge (desktop JVM): the speech recognition of the lecture assistant
# ==============================================================================
# PAGEBOARD_WHISPER_DIR: the source of a whisper.cpp release (build/whisper/src/whisper.cpp-<version>,
# prepared by the Gradle task extractWhisperSource, see native/whisper/whisper.properties).
set(PAGEBOARD_WHISPER_DIR "" CACHE PATH "Source of whisper.cpp")
file(TO_CMAKE_PATH "${PAGEBOARD_WHISPER_DIR}" PAGEBOARD_WHISPER_DIR) # Windows paths from Gradle
if(PAGEBOARD_BUILD_JNI AND NOT ANDROID AND EXISTS "${PAGEBOARD_WHISPER_DIR}/include/whisper.h")
    # Static libraries inside the bridge, for any machine of the platform (not only the one
    # building it); Metal on macOS, its shaders embedded in the library, and the encoder through Core
    # ML (the Neural Engine) when its compiled model lies next to the model, on the GPU otherwise
    # (docs/plans/transcription-queue.md, "The encoder on the Neural Engine")
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
    if(APPLE)
        set(GGML_METAL ON CACHE BOOL "" FORCE)
        set(GGML_METAL_EMBED_LIBRARY ON CACHE BOOL "" FORCE)
        set(WHISPER_COREML ON CACHE BOOL "" FORCE)
        set(WHISPER_COREML_ALLOW_FALLBACK ON CACHE BOOL "" FORCE)
    endif()
    add_subdirectory("${PAGEBOARD_WHISPER_DIR}" whisper EXCLUDE_FROM_ALL)

    add_library(whisper_bridge SHARED bridge/whisper_bridge_jni.cpp)
    target_link_libraries(whisper_bridge PRIVATE whisper)
    pageboard_configure_shared_library(whisper_bridge)
endif()

# ==============================================================================
# Audio codecs of the lecture recordings (every platform): libopus records and plays them,
# vo-aacenc exports them as AAC; both from the sources of their releases, prepared by the Gradle
# tasks extractOpusSource and extractAacSource (see native/opus/opus.properties and
# native/aac/aac.properties).
# ==============================================================================
set(PAGEBOARD_OPUS_DIR "" CACHE PATH "Source of libopus")
set(PAGEBOARD_AAC_DIR "" CACHE PATH "Source of vo-aacenc")
file(TO_CMAKE_PATH "${PAGEBOARD_OPUS_DIR}" PAGEBOARD_OPUS_DIR)
file(TO_CMAKE_PATH "${PAGEBOARD_AAC_DIR}" PAGEBOARD_AAC_DIR)
if(EXISTS "${PAGEBOARD_OPUS_DIR}/include/opus.h" AND EXISTS "${PAGEBOARD_AAC_DIR}/common/include/voAAC.h")
    set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
    set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
    set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
    set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
    set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
    set(OPUS_STATIC_RUNTIME ON CACHE BOOL "" FORCE)
    add_subdirectory("${PAGEBOARD_OPUS_DIR}" opus EXCLUDE_FROM_ALL)
    # clang-cl defines MSVC, so opus gives its SSE4.1 sources no -msse4.1 and clang refuses their
    # intrinsics. The flag goes to those sources only: the rest of opus keeps the runtime check.
    if(MSVC AND CMAKE_C_COMPILER_ID STREQUAL "Clang")
        get_target_property(OPUS_SOURCES opus SOURCES)
        list(FILTER OPUS_SOURCES INCLUDE REGEX "_sse4_1\\.c$")
        list(TRANSFORM OPUS_SOURCES PREPEND "${PAGEBOARD_OPUS_DIR}/")
        set_source_files_properties(${OPUS_SOURCES} TARGET_DIRECTORY opus PROPERTIES COMPILE_OPTIONS -msse4.1)
    endif()

    set(AAC_SRC "${PAGEBOARD_AAC_DIR}/aacenc/src")
    add_library(vo_aacenc STATIC
        "${PAGEBOARD_AAC_DIR}/common/cmnMemory.c"
        "${PAGEBOARD_AAC_DIR}/aacenc/basic_op/basicop2.c"
        "${PAGEBOARD_AAC_DIR}/aacenc/basic_op/oper_32b.c"
        "${AAC_SRC}/aac_rom.c" "${AAC_SRC}/aacenc.c" "${AAC_SRC}/aacenc_core.c" "${AAC_SRC}/adj_thr.c"
        "${AAC_SRC}/band_nrg.c" "${AAC_SRC}/bit_cnt.c" "${AAC_SRC}/bitbuffer.c" "${AAC_SRC}/bitenc.c"
        "${AAC_SRC}/block_switch.c" "${AAC_SRC}/channel_map.c" "${AAC_SRC}/dyn_bits.c" "${AAC_SRC}/grp_data.c"
        "${AAC_SRC}/interface.c" "${AAC_SRC}/line_pe.c" "${AAC_SRC}/memalign.c" "${AAC_SRC}/ms_stereo.c"
        "${AAC_SRC}/pre_echo_control.c" "${AAC_SRC}/psy_configuration.c" "${AAC_SRC}/psy_main.c" "${AAC_SRC}/qc_main.c"
        "${AAC_SRC}/quantize.c" "${AAC_SRC}/sf_estim.c" "${AAC_SRC}/spreading.c" "${AAC_SRC}/stat_bits.c"
        "${AAC_SRC}/tns.c" "${AAC_SRC}/transform.c"
    )
    target_include_directories(vo_aacenc PUBLIC
        "${PAGEBOARD_AAC_DIR}/common/include"
        "${PAGEBOARD_AAC_DIR}/aacenc/inc"
        "${PAGEBOARD_AAC_DIR}/aacenc/basic_op"
    )
    if(CMAKE_C_COMPILER_ID MATCHES "Clang|GNU")
        target_compile_options(vo_aacenc PRIVATE -w)
    endif()
    if(MSVC)
        target_compile_options(vo_aacenc PRIVATE /FIstdint.h)
    endif()

    if(PAGEBOARD_BUILD_JNI)
        add_library(audio_bridge SHARED bridge/audio_bridge.c bridge/audio_bridge_jni.c)
        pageboard_configure_shared_library(audio_bridge)
    else()
        add_library(audio_bridge STATIC bridge/audio_bridge.c)
    endif()
    target_include_directories(audio_bridge PUBLIC bridge)
    target_link_libraries(audio_bridge PRIVATE opus vo_aacenc)
endif()
