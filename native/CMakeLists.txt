cmake_minimum_required(VERSION 3.16)
project(drawview_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ==============================================================================
# Target platform
# ==============================================================================
# - Android (NDK, driven by the :drawview-native Gradle module): JNI shared libraries.
# - Desktop JVM (macOS / Linux / Windows): JNI shared libraries bundled as jvmMain resources.
# - iOS (driven by scripts/build_ios.sh): static libraries exposing the plain C API,
#   consumed by Kotlin/Native through cinterop.
if(CMAKE_SYSTEM_NAME STREQUAL "iOS")
    set(DRAWVIEW_IOS ON)
else()
    set(DRAWVIEW_IOS OFF)
endif()

if(DRAWVIEW_IOS)
    set(DRAWVIEW_DEFAULT_JNI OFF)
else()
    set(DRAWVIEW_DEFAULT_JNI ON)
endif()
option(DRAWVIEW_BUILD_JNI "Build the JNI shared libraries (Android / desktop JVM)" ${DRAWVIEW_DEFAULT_JNI})

# The Ink stroke modeller and the TeX layout engine run on the UI thread: an unoptimised
# build (Android debug variants are configured with CMAKE_BUILD_TYPE=Debug) is unusable.
if(NOT MSVC)
    add_compile_options(-O2)
endif()
add_compile_definitions(NDEBUG)

# ==============================================================================
# Abseil
# ==============================================================================
set(ABSL_PROPAGATE_CXX_STD ON CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(ABSL_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
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
if(DRAWVIEW_BUILD_JNI AND NOT ANDROID)
    if(DEFINED ENV{JAVA_HOME} AND EXISTS "$ENV{JAVA_HOME}/include")
        set(DRAWVIEW_JAVA_HOME "$ENV{JAVA_HOME}")
    elseif(EXISTS "/Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home/include")
        set(DRAWVIEW_JAVA_HOME "/Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home")
    endif()

    if(DRAWVIEW_JAVA_HOME)
        message(STATUS "Using JNI headers from: ${DRAWVIEW_JAVA_HOME}")
        set(DRAWVIEW_JNI_INCLUDE_DIRS
            "${DRAWVIEW_JAVA_HOME}/include"
            "${DRAWVIEW_JAVA_HOME}/include/darwin"
            "${DRAWVIEW_JAVA_HOME}/include/linux"
            "${DRAWVIEW_JAVA_HOME}/include/win32"
        )
    else()
        find_package(JNI REQUIRED)
        set(DRAWVIEW_JNI_INCLUDE_DIRS ${JNI_INCLUDE_DIRS})
    endif()
endif()

function(drawview_configure_shared_library target)
    if(ANDROID)
        target_link_libraries(${target} PRIVATE log)
        # 16 KB page size support (required by Android 15+ devices).
        target_link_options(${target} PRIVATE "-Wl,-z,max-page-size=16384")
    elseif(DRAWVIEW_JNI_INCLUDE_DIRS)
        target_include_directories(${target} PRIVATE ${DRAWVIEW_JNI_INCLUDE_DIRS})
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
if(DRAWVIEW_BUILD_JNI)
    add_library(ink_bridge SHARED
        bridge/ink_bridge.cpp
        bridge/ink_bridge_jni.cpp
    )
    drawview_configure_shared_library(ink_bridge)
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

if(DRAWVIEW_BUILD_JNI)
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
    drawview_configure_shared_library(tex)
else()
    # Plain C port (iOS): drawing commands are forwarded to callbacks registered by Kotlin/Native.
    add_library(tex STATIC
        ${TEX_CORE_SRCS}
        tex/port/capi/graphics_capi.cpp
        tex/port/capi/tex_capi.cpp
    )
    target_compile_definitions(tex PRIVATE DRAWVIEW_TEX_CAPI)
    target_include_directories(tex PUBLIC tex/port/capi)
endif()

# MicroTeX relies on APIs removed in C++17 (std::ptr_fun, dynamic exception specifications).
set_target_properties(tex PROPERTIES
    CXX_STANDARD 14
    CXX_STANDARD_REQUIRED ON
    CXX_EXTENSIONS OFF
)

target_compile_options(tex PRIVATE
    -Wno-dynamic-exception-spec
    -Wno-deprecated-declarations
    -Wno-non-c-typedef-for-linkage
    -Wno-parentheses
)

target_include_directories(tex PUBLIC
    tex/src
    tex/port
)

# ==============================================================================
# PDFium bridge (desktop JVM only: Android uses android.graphics.pdf.PdfRenderer and
# iOS uses CoreGraphics, both provided by the operating system)
# ==============================================================================
if(DRAWVIEW_BUILD_JNI AND NOT ANDROID AND EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/pdfium/lib/libpdfium.dylib")
    add_library(pdfium_bridge SHARED
        bridge/pdfium_bridge.cpp
    )
    target_include_directories(pdfium_bridge PUBLIC
        bridge
        pdfium/include
    )
    target_link_directories(pdfium_bridge PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/pdfium/lib
    )
    target_link_libraries(pdfium_bridge PRIVATE
        pdfium
    )
    drawview_configure_shared_library(pdfium_bridge)
endif()
