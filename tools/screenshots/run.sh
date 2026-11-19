#!/usr/bin/env bash
# Records, verifies or compares the screenshots in the reference environment, the image of
# tools/screenshots/Dockerfile, on any computer with Docker (docs/plans/ci-screenshots.md).
#
# Usage: tools/screenshots/run.sh record|verify|compare [gradle arguments...]
#
# The checkout is mounted at /work and the build writes in it (build/ of every module, the JNI
# libraries of linux-x86_64 in build/nativeLibs). The Gradle home of the container is the volume
# drawview-screenshots-gradle, or the directory SCREENSHOTS_GRADLE_HOME (the workflow gives one
# that it caches). The project cache of Gradle is kept apart from the one of the host.
# SCREENSHOTS_NO_BUILD=1 uses the image as it is (the workflow builds it with its own cache).
#
# On Apple silicon the image is x86_64 and emulated: the first run builds the JNI libraries under
# emulation and takes long; the next ones take them from the build cache.
set -euo pipefail
mode=${1:?usage: run.sh record|verify|compare [gradle arguments...]}
shift
root=$(cd "$(dirname "$0")/../.." && pwd)
image=${SCREENSHOTS_IMAGE:-drawview-screenshots}
toolchain=$(sed -n 's/^channel *= *"\(.*\)"/\1/p' "$root/native/iroh-bridge/rust-toolchain.toml")

if [ -z "${SCREENSHOTS_NO_BUILD:-}" ]; then
  docker build --platform linux/amd64 --build-arg RUST_TOOLCHAIN="$toolchain" -t "$image" "$root/tools/screenshots"
fi

gradle_home=${SCREENSHOTS_GRADLE_HOME:-drawview-screenshots-gradle}
mounts=(-v "$root:/work" -v "$gradle_home:/gradle")
# The local.properties of the host names its Android SDK and its cargo, which are not in the
# image: the container sees an empty one (the JVM tests need neither)
if [ -f "$root/local.properties" ]; then
  mkdir -p "$root/build/screenshots"
  : > "$root/build/screenshots/local.properties"
  mounts+=(-v "$root/build/screenshots/local.properties:/work/local.properties:ro")
fi
# One project cache for each checkout, all of them being /work in the container
project_cache=/gradle/project-cache/$(printf %s "$root" | cksum | cut -d' ' -f1)

docker run --rm --platform linux/amd64 \
  --user "$(id -u):$(id -g)" \
  "${mounts[@]}" \
  "$image" \
  tools/screenshots/gradle.sh "$mode" --project-cache-dir "$project_cache" "$@"
