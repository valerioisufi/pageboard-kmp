#!/usr/bin/env bash
# Records or verifies the screenshots of every module that applies pageboard.screenshots, in the
# reference environment (the workflow Screenshots, or the image of tools/screenshots/Dockerfile).
# Usage: tools/screenshots/gradle.sh record|verify|compare [gradle arguments...]
set -euo pipefail
mode=${1:?usage: gradle.sh record|verify|compare [gradle arguments...]}
shift
case "$mode" in
  record) task=recordRoborazziJvm ;;
  verify) task=verifyRoborazziJvm ;;
  compare) task=compareRoborazziJvm ;;
  *) echo "usage: gradle.sh record|verify|compare [gradle arguments...]" >&2; exit 2 ;;
esac
cd "$(dirname "$0")/../.."
tasks=()
for build in */build.gradle.kts; do
  if grep -q 'id("pageboard.screenshots")' "$build"; then
    tasks+=(":$(dirname "$build"):$task")
  fi
done
# The tests of these modules open AWT windows: on Linux without a display they get a virtual one
run=()
if [ "$(uname -s)" = Linux ] && [ -z "${DISPLAY:-}" ]; then
  run=(xvfb-run -a -s "-screen 0 1920x1080x24")
fi
${run[@]+"${run[@]}"} ./gradlew "${tasks[@]}" --continue "$@"
