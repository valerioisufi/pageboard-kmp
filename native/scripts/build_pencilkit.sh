#!/usr/bin/env bash
# Builds the PencilKit bridge (bridge/pencilkit_bridge.swift, the handwriting recognizer of the
# systems 27) with the Swift compiler of Xcode:
#   iphoneos, iphonesimulator: <out>/libpencilkit_bridge.a, a static library for cinterop
#   darwin-aarch64, darwin-x86_64: <out>/libpencilkit_bridge.dylib, the JNI library (with
#     bridge/pencilkit_bridge_jni.c, the JNI headers of JAVA_HOME)
#
# Usage: native/scripts/build_pencilkit.sh <iphoneos|iphonesimulator|darwin-aarch64|darwin-x86_64> <out>
#
# With an SDK without PKStrokeRecognizer (before Xcode 27) the bridge is built without the
# recognizer: it reads no language, and the applications build all the same.
set -euo pipefail

PLATFORM="${1:?usage: build_pencilkit.sh <platform> <out>}"
OUT="${2:?usage: build_pencilkit.sh <platform> <out>}"
NATIVE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BRIDGE="$NATIVE_DIR/bridge"

# Gradle may run with a JDK-provided environment: make sure the Xcode toolchain is used
unset CC CXX CFLAGS CXXFLAGS LDFLAGS SDKROOT

case "$PLATFORM" in
    iphoneos) SDK=iphoneos; TARGET=arm64-apple-ios16.0 ;;
    iphonesimulator) SDK=iphonesimulator; TARGET=arm64-apple-ios16.0-simulator ;;
    darwin-aarch64) SDK=macosx; TARGET=arm64-apple-macos13.0 ;;
    darwin-x86_64) SDK=macosx; TARGET=x86_64-apple-macos13.0 ;;
    *) echo "Unsupported platform: $PLATFORM" >&2; exit 1 ;;
esac
SDK_PATH="$(xcrun --sdk "$SDK" --show-sdk-path)"

# The recognizer only with an SDK that has it
DEFINES=()
if grep -qs "actor PKStrokeRecognizer" "$SDK_PATH"/System/Library/Frameworks/PencilKit.framework/Modules/PencilKit.swiftmodule/*.swiftinterface \
    "$SDK_PATH"/System/Library/Frameworks/PencilKit.framework/Versions/A/Modules/PencilKit.swiftmodule/*.swiftinterface; then
    DEFINES=(-D PAGEBOARD_STROKE_RECOGNIZER)
else
    echo "warning: the SDK $SDK_PATH has no PKStrokeRecognizer: the PencilKit bridge reads nothing" >&2
fi

BUILD="$OUT/obj"
mkdir -p "$BUILD"
SWIFT_FLAGS=(-O -swift-version 5 -sdk "$SDK_PATH" -target "$TARGET" -module-name PageboardPencilKit
    -import-objc-header "$BRIDGE/pencilkit_bridge.h" ${DEFINES[@]+"${DEFINES[@]}"})

case "$PLATFORM" in
    iphoneos | iphonesimulator)
        # The object file of the bridge in a static library; the Swift runtime of the system is
        # linked by the application (the autolink entries of the object file), without the
        # compatibility libraries of the toolchain, which the linker of Kotlin/Native does not find
        xcrun --sdk "$SDK" swiftc "${SWIFT_FLAGS[@]}" -runtime-compatibility-version none -parse-as-library -emit-object -whole-module-optimization \
            "$BRIDGE/pencilkit_bridge.swift" -o "$BUILD/pencilkit_bridge.o"
        rm -f "$OUT/libpencilkit_bridge.a"
        xcrun libtool -static -o "$OUT/libpencilkit_bridge.a" "$BUILD/pencilkit_bridge.o"
        echo "Built $OUT/libpencilkit_bridge.a"
        ;;
    darwin-*)
        JAVA="${JAVA_HOME:?JAVA_HOME must point to a JDK (the JNI headers)}"
        ARCH="${TARGET%%-*}"
        xcrun clang -O2 -arch "$ARCH" -mmacosx-version-min=13.0 -isysroot "$SDK_PATH" -I "$BRIDGE" \
            -I "$JAVA/include" -I "$JAVA/include/darwin" -c "$BRIDGE/pencilkit_bridge_jni.c" -o "$BUILD/pencilkit_bridge_jni.o"
        xcrun --sdk macosx swiftc "${SWIFT_FLAGS[@]}" -parse-as-library -emit-library "$BRIDGE/pencilkit_bridge.swift" \
            "$BUILD/pencilkit_bridge_jni.o" -o "$OUT/libpencilkit_bridge.dylib" -Xlinker -install_name -Xlinker @rpath/libpencilkit_bridge.dylib
        echo "Built $OUT/libpencilkit_bridge.dylib"
        ;;
esac
