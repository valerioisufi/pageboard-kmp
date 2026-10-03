#!/usr/bin/env bash
# Copies PDFium.framework (slice of the current SDK of build/pdfium/PDFium.xcframework) into the
# Frameworks folder of the app being built by Xcode and signs it with the app identity.
# Run from the "Compile Kotlin Framework" build phase through ./gradlew :embedPdfiumForXcode.
set -euo pipefail

: "${PLATFORM_NAME:?must be run from an Xcode build phase}"
: "${TARGET_BUILD_DIR:?}" "${FRAMEWORKS_FOLDER_PATH:?}"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
case "$PLATFORM_NAME" in
    iphoneos) SLICE="ios-arm64" ;;
    iphonesimulator) SLICE="ios-arm64-simulator" ;;
    *) echo "Unsupported platform: $PLATFORM_NAME" >&2; exit 1 ;;
esac

SOURCE="$ROOT_DIR/build/pdfium/PDFium.xcframework/$SLICE/PDFium.framework"
DEST_DIR="$TARGET_BUILD_DIR/$FRAMEWORKS_FOLDER_PATH"
mkdir -p "$DEST_DIR"
rsync -a --delete --exclude Headers --exclude _CodeSignature "$SOURCE" "$DEST_DIR/"

IDENTITY="${EXPANDED_CODE_SIGN_IDENTITY:--}"
if [[ "${CODE_SIGNING_ALLOWED:-YES}" == "YES" ]]; then
    codesign --force --sign "${IDENTITY:--}" --timestamp=none --preserve-metadata=identifier,entitlements "$DEST_DIR/PDFium.framework"
fi
echo "Embedded $DEST_DIR/PDFium.framework"
