#!/usr/bin/env bash
# Builds the JNI libraries used by the desktop (JVM) targets (and whisper.cpp, built from the
# source of its release) into
#   build/nativeLibs/jvm/<module>/native/<platform>/
# which Gradle adds to the jvmMain resources of the Kotlin modules, where the native loaders
# look for them. libpdfium itself is added by Gradle too (preparePdfiumJvmResources).
#
# Usage: native/scripts/build_desktop.sh [platform]
#   platform: darwin-aarch64, darwin-x86_64, linux-x86_64, linux-aarch64, windows-x86_64 or
#             windows-aarch64 (default: the host). macOS hosts can build both darwin platforms;
#             the other platforms must be built on a host of the same OS and architecture.
#   Normally run through ./gradlew buildDesktopNativeLibs (-Pdrawview.desktop.platform=...),
#   which provides DRAWVIEW_PDFIUM_DIR (the extracted pdfium-binaries package).
#   Windows hosts need Git Bash and Visual Studio with the "C++ Clang tools" (clang-cl).
set -euo pipefail

NATIVE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT_DIR="$(cd "$NATIVE_DIR/.." && pwd)"

case "$(uname -s)" in
    Darwin) HOST_OS="darwin" ;;
    Linux) HOST_OS="linux" ;;
    MINGW* | MSYS* | CYGWIN*) HOST_OS="windows" ;;
    *) echo "Unsupported host: $(uname -s)" >&2; exit 1 ;;
esac
case "$(uname -m)" in
    arm64 | aarch64) HOST_ARCH="aarch64" ;;
    *) HOST_ARCH="x86_64" ;;
esac
PLATFORM="${1:-$HOST_OS-$HOST_ARCH}"

PREFIX="lib"
CMAKE_ARGS=()
BUILD_ARGS=()
case "$PLATFORM" in
    darwin-aarch64 | darwin-x86_64)
        EXT="dylib"
        if [[ "$PLATFORM" == "darwin-aarch64" ]]; then OSX_ARCH="arm64"; PDFIUM_PACKAGE="mac-arm64"; else OSX_ARCH="x86_64"; PDFIUM_PACKAGE="mac-x64"; fi
        CMAKE_ARGS=(-DCMAKE_OSX_ARCHITECTURES="$OSX_ARCH" -DCMAKE_OSX_DEPLOYMENT_TARGET=13.0)
        ;;
    linux-x86_64 | linux-aarch64)
        EXT="so"
        if [[ "$PLATFORM" == "linux-aarch64" ]]; then PDFIUM_PACKAGE="linux-arm64"; else PDFIUM_PACKAGE="linux-x64"; fi
        ;;
    windows-x86_64 | windows-aarch64)
        EXT="dll"
        PREFIX=""
        if [[ "$PLATFORM" == "windows-aarch64" ]]; then VS_ARCH="ARM64"; PDFIUM_PACKAGE="win-arm64"; else VS_ARCH="x64"; PDFIUM_PACKAGE="win-x64"; fi
        # Visual Studio generator with the clang-cl toolset: the engines rely on GCC/Clang flags
        CMAKE_ARGS=(-A "$VS_ARCH" -T ClangCL)
        BUILD_ARGS=(--config Release)
        ;;
    *)
        echo "Unsupported platform: $PLATFORM" >&2
        exit 1
        ;;
esac
if [[ "$PLATFORM" != "$HOST_OS"-* || ( "$HOST_OS" != "darwin" && "$PLATFORM" != "$HOST_OS-$HOST_ARCH" ) ]]; then
    echo "Cannot build $PLATFORM on a $HOST_OS-$HOST_ARCH host" >&2
    exit 1
fi

# Prebuilt PDFium package (downloaded and verified by Gradle)
PDFIUM_DIR="${DRAWVIEW_PDFIUM_DIR:-}"
if [[ -z "$PDFIUM_DIR" ]]; then
    PDFIUM_DIR="$ROOT_DIR/build/pdfium/$PDFIUM_PACKAGE"
    "$ROOT_DIR/gradlew" -p "$ROOT_DIR" -q ":extractPdfium-$PDFIUM_PACKAGE"
fi

# Source of whisper.cpp (downloaded and verified by Gradle)
WHISPER_DIR="${DRAWVIEW_WHISPER_DIR:-}"
if [[ -z "$WHISPER_DIR" ]]; then
    WHISPER_VERSION="$(sed -n 's/^version=//p' "$NATIVE_DIR/whisper/whisper.properties")"
    WHISPER_DIR="$ROOT_DIR/build/whisper/src/whisper.cpp-$WHISPER_VERSION"
    "$ROOT_DIR/gradlew" -p "$ROOT_DIR" -q ":extractWhisperSource"
fi

# Sources of libopus and vo-aacenc (downloaded and verified by Gradle)
OPUS_DIR="${DRAWVIEW_OPUS_DIR:-}"
AAC_DIR="${DRAWVIEW_AAC_DIR:-}"
if [[ -z "$OPUS_DIR" || -z "$AAC_DIR" ]]; then
    OPUS_DIR="$ROOT_DIR/build/opus/src/opus-$(sed -n 's/^version=//p' "$NATIVE_DIR/opus/opus.properties")"
    AAC_DIR="$ROOT_DIR/build/aac/src/vo-aacenc-$(sed -n 's/^version=//p' "$NATIVE_DIR/aac/aac.properties")"
    "$ROOT_DIR/gradlew" -p "$ROOT_DIR" -q ":extractOpusSource" ":extractAacSource"
fi

BUILD_DIR="$NATIVE_DIR/build/desktop-$PLATFORM"
cmake -S "$NATIVE_DIR" -B "$BUILD_DIR" -DCMAKE_BUILD_TYPE=Release -DDRAWVIEW_BUILD_JNI=ON \
    -DDRAWVIEW_PDFIUM_DIR="$PDFIUM_DIR" -DDRAWVIEW_WHISPER_DIR="$WHISPER_DIR" \
    -DDRAWVIEW_OPUS_DIR="$OPUS_DIR" -DDRAWVIEW_AAC_DIR="$AAC_DIR" ${CMAKE_ARGS[@]+"${CMAKE_ARGS[@]}"}
TARGETS=(ink_bridge tex pdfium_bridge whisper_bridge audio_bridge)
if [[ "$PLATFORM" == darwin-* ]]; then
    TARGETS+=(trackpad_bridge) # scroll phases and pinch of the trackpad
    TARGETS+=(audio_codec_bridge) # audio files of other applications, AAC
fi
cmake --build "$BUILD_DIR" ${BUILD_ARGS[@]+"${BUILD_ARGS[@]}"} --target "${TARGETS[@]}" \
    -j "$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"

OUT_DIR="$ROOT_DIR/build/nativeLibs/jvm"
# The libraries this build installs only: none left behind by another layout of the modules
for dir in "$OUT_DIR"/*/native/"$PLATFORM"; do
    if [[ -d "$dir" ]]; then rm -r "$dir"; fi
done
install_lib() {
    local name="$1" module="$2"
    local file="$PREFIX$name.$EXT"
    local built="$BUILD_DIR/$file"
    [[ -f "$built" ]] || built="$BUILD_DIR/Release/$file" # multi-config generators (Visual Studio)
    local dest="$OUT_DIR/$module/native/$PLATFORM"
    mkdir -p "$dest"
    cp "$built" "$dest/$file"
    echo "Installed $dest/$file"
}

install_lib ink_bridge drawview-ink
if [[ "$PLATFORM" == darwin-* ]]; then
    install_lib trackpad_bridge drawview-core
fi
cp "$NATIVE_DIR/ink/LICENSE" "$OUT_DIR/drawview-ink/native/$PLATFORM/INK_LICENSE.txt"
install_lib tex drawview-latex
install_lib pdfium_bridge drawview-pdf
install_lib whisper_bridge drawview-lecture
install_lib audio_bridge drawview-lecture
cp "$OPUS_DIR/COPYING" "$OUT_DIR/drawview-lecture/native/$PLATFORM/OPUS_LICENSE.txt"
cp "$AAC_DIR/COPYING" "$OUT_DIR/drawview-lecture/native/$PLATFORM/VO_AACENC_LICENSE.txt"
if [[ "$PLATFORM" == darwin-* ]]; then
    install_lib audio_codec_bridge drawview-lecture
fi
cp "$WHISPER_DIR/LICENSE" "$OUT_DIR/drawview-lecture/native/$PLATFORM/WHISPER_CPP_LICENSE.txt"
