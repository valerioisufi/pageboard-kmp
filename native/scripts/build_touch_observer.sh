#!/usr/bin/env bash
# Builds the observer of the touches of iOS (bridge/touch_observer.m) into a static library for
# cinterop: <out>/libtouch_observer.a.
#
# Usage: native/scripts/build_touch_observer.sh <iphoneos|iphonesimulator> <out>
set -euo pipefail

PLATFORM="${1:?usage: build_touch_observer.sh <platform> <out>}"
OUT="${2:?usage: build_touch_observer.sh <platform> <out>}"
NATIVE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BRIDGE="$NATIVE_DIR/bridge"

# Gradle may run with a JDK-provided environment: make sure the Xcode toolchain is used
unset CC CXX CFLAGS CXXFLAGS LDFLAGS SDKROOT

case "$PLATFORM" in
    iphoneos) SDK=iphoneos; TARGET=arm64-apple-ios16.0 ;;
    iphonesimulator) SDK=iphonesimulator; TARGET=arm64-apple-ios16.0-simulator ;;
    *) echo "Unsupported platform: $PLATFORM" >&2; exit 1 ;;
esac
SDK_PATH="$(xcrun --sdk "$SDK" --show-sdk-path)"

BUILD="$OUT/obj"
mkdir -p "$BUILD"
xcrun --sdk "$SDK" clang -O2 -fobjc-arc -target "$TARGET" -isysroot "$SDK_PATH" -I "$BRIDGE" \
    -c "$BRIDGE/touch_observer.m" -o "$BUILD/touch_observer.o"
rm -f "$OUT/libtouch_observer.a"
xcrun libtool -static -o "$OUT/libtouch_observer.a" "$BUILD/touch_observer.o"
echo "Built $OUT/libtouch_observer.a"
