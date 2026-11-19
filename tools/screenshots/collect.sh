#!/usr/bin/env bash
# Collects what a verify of the screenshots left in build/outputs/roborazzi of each module:
#   <out>/diff/<module>/<name>_compare.png  the differences, to look at;
#   <out>/new/<module>/screenshots/<name>.png  the images rendered by the run, at their paths in
#   the repository, to unzip at the root of a checkout and commit when the change is meant.
# Usage: tools/screenshots/collect.sh <out>
set -euo pipefail
out=${1:?usage: collect.sh <out>}
cd "$(dirname "$0")/../.."
rm -rf "$out"
mkdir -p "$out/diff" "$out/new"
found=0
for dir in */build/outputs/roborazzi; do
  [ -d "$dir" ] || continue
  module=${dir%%/*}
  for image in "$dir"/*_compare.png; do
    [ -e "$image" ] || continue
    mkdir -p "$out/diff/$module"
    cp "$image" "$out/diff/$module/"
  done
  for image in "$dir"/*_actual.png; do
    [ -e "$image" ] || continue
    name=$(basename "$image" _actual.png)
    mkdir -p "$out/new/$module/screenshots"
    cp "$image" "$out/new/$module/screenshots/$name.png"
    found=$((found + 1))
  done
done
echo "$found screenshots differ or have no reference"
