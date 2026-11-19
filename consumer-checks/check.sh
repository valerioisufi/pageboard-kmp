#!/usr/bin/env bash
# The libraries of Pageboard as another developer uses them: published to mavenLocal(), then
# resolved by the separate build of consumer-checks/ (see its settings.gradle.kts).
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew publishToMavenLocal
./gradlew -p consumer-checks check
