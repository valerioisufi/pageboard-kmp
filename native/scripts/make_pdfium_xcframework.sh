#!/usr/bin/env bash
# Wraps the iOS PDFium dylibs of pdfium-binaries into PDFium.xcframework: iOS apps can only
# embed dynamic libraries as frameworks, and the prebuilt dylibs carry a relative install name
# (./libpdfium.dylib) that must become @rpath/PDFium.framework/PDFium.
#
# Usage: make_pdfium_xcframework.sh <version> <output.xcframework> <package-dir>...
#   <package-dir>: an extracted pdfium-ios-* package (lib/libpdfium.dylib, include/)
set -euo pipefail

VERSION="${1:?usage: make_pdfium_xcframework.sh <version> <output.xcframework> <package-dir>...}"
OUTPUT="${2:?missing output path}"
shift 2

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

FRAMEWORK_ARGS=()
for PACKAGE in "$@"; do
    NAME="$(basename "$PACKAGE")"
    FRAMEWORK="$WORK_DIR/$NAME/PDFium.framework"
    mkdir -p "$FRAMEWORK/Headers"

    cp "$PACKAGE/lib/libpdfium.dylib" "$FRAMEWORK/PDFium"
    install_name_tool -id "@rpath/PDFium.framework/PDFium" "$FRAMEWORK/PDFium" 2>/dev/null
    cp -R "$PACKAGE/include/." "$FRAMEWORK/Headers/"
    rm -f "$FRAMEWORK/Headers/"*.orig

    MIN_OS="$(otool -l "$FRAMEWORK/PDFium" | awk '/LC_BUILD_VERSION/ { found = 1 } found && $1 == "minos" { print $2; exit }')"
    cat > "$FRAMEWORK/Info.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleDevelopmentRegion</key>
    <string>en</string>
    <key>CFBundleExecutable</key>
    <string>PDFium</string>
    <key>CFBundleIdentifier</key>
    <string>io.github.valerioisufi.drawview.pdfium</string>
    <key>CFBundleInfoDictionaryVersion</key>
    <string>6.0</string>
    <key>CFBundleName</key>
    <string>PDFium</string>
    <key>CFBundlePackageType</key>
    <string>FMWK</string>
    <key>CFBundleShortVersionString</key>
    <string>$VERSION.0.0</string>
    <key>CFBundleVersion</key>
    <string>$VERSION</string>
    <key>MinimumOSVersion</key>
    <string>$MIN_OS</string>
</dict>
</plist>
EOF
    # install_name_tool invalidates the signature: re-sign ad hoc (Xcode signs again on embed).
    codesign --force --sign - "$FRAMEWORK/PDFium" >/dev/null 2>&1

    FRAMEWORK_ARGS+=(-framework "$FRAMEWORK")
done

rm -rf "$OUTPUT"
xcodebuild -create-xcframework "${FRAMEWORK_ARGS[@]}" -output "$OUTPUT" >/dev/null
echo "Created $OUTPUT"
