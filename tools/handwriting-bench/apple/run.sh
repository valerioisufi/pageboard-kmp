#!/usr/bin/env bash
# Builds the bench of PencilKit (PencilKitBench.swift with the bridge of Pageboard) as an application
# and runs it in the foreground, where PencilKit reads: on this Mac (macOS 27) or in a simulator of
# iPadOS 27 (SIMULATOR: a name or UDID, by default the first iPad available).
#
#   tools/handwriting-bench/apple/run.sh <macos|simulator> <directory of InkML> <readings.tsv> [points per mm]
#
# Built in native/build/handwriting-bench; nothing is installed outside it but the application in the
# simulator.
set -euo pipefail

PLATFORM="${1:?usage: run.sh <macos|simulator> <directory> <readings.tsv> [points per mm]}"
INK="$(cd "${2:?}" && pwd)"
OUTPUT="$(cd "$(dirname "${3:?}")" && pwd)/$(basename "$3")"
SCALE="${4:-5.2}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
BRIDGE="$ROOT/native/bridge"
BUILD="$ROOT/native/build/handwriting-bench/$PLATFORM"
ID="io.github.valerioisufi.pageboard.handwritingbench"
rm -rf "$BUILD" "$OUTPUT"
mkdir -p "$BUILD"

info_plist() {
    cat <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>$ID</string>
<key>CFBundleExecutable</key><string>PencilKitBench</string>
<key>CFBundleName</key><string>PencilKitBench</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleVersion</key><string>1</string>
<key>CFBundleShortVersionString</key><string>1.0</string>
$1
</dict></plist>
EOF
}

case "$PLATFORM" in
    macos)
        APP="$BUILD/PencilKitBench.app"
        mkdir -p "$APP/Contents/MacOS"
        xcrun swiftc -O -swift-version 5 -D PAGEBOARD_STROKE_RECOGNIZER -import-objc-header "$BRIDGE/pencilkit_bridge.h" \
            "$BRIDGE/pencilkit_bridge.swift" "$HERE/PencilKitBench.swift" -o "$APP/Contents/MacOS/PencilKitBench"
        info_plist "<key>LSMinimumSystemVersion</key><string>27.0</string>" > "$APP/Contents/Info.plist"
        codesign -s - --force "$APP" >/dev/null 2>&1
        open -W -n "$APP" --args "$INK" "$OUTPUT" "$SCALE"
        ;;
    simulator)
        APP="$BUILD/PencilKitBench.app"
        mkdir -p "$APP"
        xcrun --sdk iphonesimulator swiftc -O -swift-version 5 -target arm64-apple-ios27.0-simulator -D PAGEBOARD_STROKE_RECOGNIZER \
            -import-objc-header "$BRIDGE/pencilkit_bridge.h" "$BRIDGE/pencilkit_bridge.swift" "$HERE/PencilKitBench.swift" \
            -o "$APP/PencilKitBench"
        info_plist "<key>MinimumOSVersion</key><string>27.0</string>
<key>UIDeviceFamily</key><array><integer>1</integer><integer>2</integer></array>
<key>UILaunchScreen</key><dict/>
<key>UIApplicationSceneManifest</key><dict><key>UIApplicationSupportsMultipleScenes</key><false/></dict>
<key>UIRequiredDeviceCapabilities</key><array><string>arm64</string></array>" > "$APP/Info.plist"
        codesign -s - --force "$APP" >/dev/null 2>&1
        DEVICE="${SIMULATOR:-$(xcrun simctl list devices available | grep -m1 -E '^\s+iPad' | sed -E 's/.*\(([0-9A-F-]{36})\).*/\1/')}"
        xcrun simctl boot "$DEVICE" 2>/dev/null || true
        xcrun simctl bootstatus "$DEVICE" -b >/dev/null
        xcrun simctl install "$DEVICE" "$APP"
        xcrun simctl launch --console --terminate-running-process "$DEVICE" "$ID" "$INK" "$OUTPUT" "$SCALE" >/dev/null
        xcrun simctl uninstall "$DEVICE" "$ID"
        ;;
    *)
        echo "Unknown platform: $PLATFORM" >&2
        exit 1
        ;;
esac
[[ -f "$OUTPUT" ]] || { echo "No readings written" >&2; exit 1; }
cat "$OUTPUT.info"
echo "Readings: $OUTPUT ($(wc -l < "$OUTPUT" | tr -d ' ') lines)"
