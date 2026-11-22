#!/usr/bin/env bash
# The libraries of Pageboard as another developer uses them: published to mavenLocal(), then
# resolved by the separate build of consumer-checks/ (see its settings.gradle.kts).
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew publishToMavenLocal
# The PDFium of the iOS applications, which library-ios links (macOS only)
if [[ "$(uname -s)" == "Darwin" ]]; then ./gradlew assemblePdfiumXcframework; fi
./gradlew -p consumer-checks check
