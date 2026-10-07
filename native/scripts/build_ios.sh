#!/usr/bin/env bash
# Builds the static libraries consumed by the Kotlin/Native iOS targets through cinterop:
#   native/build/ios/<sdk>/lib/libdrawview_ink.a   Google Ink (+ Abseil, libtess2) C API
#   native/build/ios/<sdk>/lib/libdrawview_tex.a   MicroTeX C API
#   native/build/ios/<sdk>/lib/libdrawview_pdf.a   PDFium bridge C API (PDFium itself is
#                                                  build/pdfium/PDFium.xcframework)
#   native/build/ios/<sdk>/lib/libdrawview_audio.a the audio codecs (libopus, vo-aacenc)
#
# Usage: native/scripts/build_ios.sh <iphoneos|iphonesimulator>
# DRAWVIEW_PDFIUM_DIR: the extracted pdfium-ios-* package of the SDK (headers of the bridge),
# set by the Gradle tasks buildIosNativeLibs-<sdk>.
set -euo pipefail

SDK="${1:?usage: build_ios.sh <iphoneos|iphonesimulator>}"
NATIVE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="$NATIVE_DIR/build/ios/$SDK"
OUT_DIR="$BUILD_DIR/lib"
DEPLOYMENT_TARGET="${IOS_DEPLOYMENT_TARGET:-15.0}"
PDFIUM_DIR="${DRAWVIEW_PDFIUM_DIR:?DRAWVIEW_PDFIUM_DIR must point to the extracted pdfium-ios package}"
OPUS_DIR="${DRAWVIEW_OPUS_DIR:?DRAWVIEW_OPUS_DIR must point to the source of libopus}"
AAC_DIR="${DRAWVIEW_AAC_DIR:?DRAWVIEW_AAC_DIR must point to the source of vo-aacenc}"

# Gradle may run with a JDK-provided environment: make sure the Xcode toolchain is used.
unset CC CXX CFLAGS CXXFLAGS LDFLAGS SDKROOT

mkdir -p "$BUILD_DIR"

cmake -S "$NATIVE_DIR" -B "$BUILD_DIR" \
    -G "Unix Makefiles" \
    -DCMAKE_SYSTEM_NAME=iOS \
    -DCMAKE_OSX_SYSROOT="$SDK" \
    -DCMAKE_OSX_ARCHITECTURES=arm64 \
    -DCMAKE_OSX_DEPLOYMENT_TARGET="$DEPLOYMENT_TARGET" \
    -DCMAKE_BUILD_TYPE=Release \
    -DDRAWVIEW_BUILD_JNI=OFF \
    -DDRAWVIEW_PDFIUM_DIR="$PDFIUM_DIR" \
    -DDRAWVIEW_OPUS_DIR="$OPUS_DIR" \
    -DDRAWVIEW_AAC_DIR="$AAC_DIR" \
    > "$BUILD_DIR/configure.log" 2>&1 || { cat "$BUILD_DIR/configure.log"; exit 1; }

JOBS="$(sysctl -n hw.ncpu 2>/dev/null || echo 4)"
# Building the static targets also builds (target-level dependencies) Abseil and libtess2.
cmake --build "$BUILD_DIR" --target ink_bridge tex pdfium_bridge audio_bridge opus vo_aacenc -j "$JOBS"

mkdir -p "$OUT_DIR"

# One archive per engine, so that each Kotlin module links only what it uses.
INK_ARCHIVES=("$BUILD_DIR/libink_bridge.a" "$BUILD_DIR/libink_core.a" "$BUILD_DIR/liblibtess2.a")
while IFS= read -r archive; do
    INK_ARCHIVES+=("$archive")
done < <(find "$BUILD_DIR/external/abseil-cpp" -name "*.a" | sort)

rm -f "$OUT_DIR/libdrawview_ink.a" "$OUT_DIR/libdrawview_tex.a" "$OUT_DIR/libdrawview_pdf.a" "$OUT_DIR/libdrawview_audio.a"
xcrun libtool -static -no_warning_for_no_symbols -o "$OUT_DIR/libdrawview_ink.a" "${INK_ARCHIVES[@]}" 2> >(grep -v "same member name" >&2)
xcrun libtool -static -no_warning_for_no_symbols -o "$OUT_DIR/libdrawview_tex.a" "$BUILD_DIR/libtex.a"
xcrun libtool -static -no_warning_for_no_symbols -o "$OUT_DIR/libdrawview_pdf.a" "$BUILD_DIR/libpdfium_bridge.a"
xcrun libtool -static -no_warning_for_no_symbols -o "$OUT_DIR/libdrawview_audio.a" \
    "$BUILD_DIR/libaudio_bridge.a" "$BUILD_DIR/opus/libopus.a" "$BUILD_DIR/libvo_aacenc.a"

echo "Built $OUT_DIR/libdrawview_{ink,tex,pdf,audio}.a"
