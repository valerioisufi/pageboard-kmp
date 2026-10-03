#!/usr/bin/env bash
# Builds the JNI libraries used by the desktop (JVM) targets and copies them into the
# jvmMain resources of the Kotlin modules, where the native loaders look for them.
#
# Usage: native/scripts/build_desktop.sh
set -euo pipefail

NATIVE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT_DIR="$(cd "$NATIVE_DIR/.." && pwd)"
BUILD_DIR="$NATIVE_DIR/build/desktop"

OS="$(uname -s)"
ARCH="$(uname -m)"
case "$OS" in
    Darwin)
        EXT="dylib"
        case "$ARCH" in
            arm64) PLATFORM="darwin-aarch64"; OSX_ARCH="arm64" ;;
            *) PLATFORM="darwin-x86_64"; OSX_ARCH="x86_64" ;;
        esac
        EXTRA_ARGS=(-DCMAKE_OSX_ARCHITECTURES="$OSX_ARCH" -DCMAKE_OSX_DEPLOYMENT_TARGET=13.0)
        ;;
    Linux)
        EXT="so"
        case "$ARCH" in
            aarch64) PLATFORM="linux-aarch64" ;;
            *) PLATFORM="linux-x86_64" ;;
        esac
        EXTRA_ARGS=()
        ;;
    *)
        echo "Unsupported host: $OS" >&2
        exit 1
        ;;
esac

cmake -S "$NATIVE_DIR" -B "$BUILD_DIR" -DCMAKE_BUILD_TYPE=Release -DDRAWVIEW_BUILD_JNI=ON "${EXTRA_ARGS[@]}"

TARGETS=(ink_bridge tex)
if [[ "$OS" == "Darwin" && -f "$NATIVE_DIR/pdfium/lib/libpdfium.dylib" ]]; then
    TARGETS+=(pdfium_bridge)
fi
cmake --build "$BUILD_DIR" --target "${TARGETS[@]}" -j "$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"

install_lib() {
    local file="$1" module="$2"
    local dest="$ROOT_DIR/$module/src/jvmMain/resources/native/$PLATFORM"
    mkdir -p "$dest"
    cp "$BUILD_DIR/$file" "$dest/$file"
    echo "Installed $dest/$file"
}

install_lib "libink_bridge.$EXT" drawview-core
cp "$NATIVE_DIR/ink/LICENSE" "$ROOT_DIR/drawview-core/src/jvmMain/resources/native/$PLATFORM/INK_LICENSE.txt"
install_lib "libtex.$EXT" drawview-latex
if [[ " ${TARGETS[*]} " == *" pdfium_bridge "* ]]; then
    install_lib "libpdfium_bridge.$EXT" drawview-pdf
    cp "$NATIVE_DIR/pdfium/lib/libpdfium.dylib" "$ROOT_DIR/drawview-pdf/src/jvmMain/resources/native/$PLATFORM/libpdfium.dylib"
    cp "$NATIVE_DIR/pdfium/LICENSE" "$ROOT_DIR/drawview-pdf/src/jvmMain/resources/native/$PLATFORM/PDFIUM_LICENSE.txt"
    cp -r "$NATIVE_DIR/pdfium/licenses" "$ROOT_DIR/drawview-pdf/src/jvmMain/resources/native/$PLATFORM/"
fi
