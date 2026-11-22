#!/usr/bin/env bash
# Checks the JNI libraries of Linux against the oldest systems the libraries run on: no symbol of
# glibc newer than 2.28 (manylinux_2_28: RHEL 8, Debian 10, Ubuntu 18.10 and later) and no libstdc++
# to load (it is linked inside them, see CMakeLists.txt).
#
# Usage: native/scripts/check_linux_libraries.sh [directory...]
#   directory: where the libraries are (default: build/nativeLibs/jvm, as buildDesktopNativeLibs
#              writes them: <module>/native/linux-<architecture>/*.so)
set -euo pipefail

MAX_GLIBC="2.28"
if [[ $# -gt 0 ]]; then directories=("$@"); else directories=(build/nativeLibs/jvm); fi

failed=0
found=0
while IFS= read -r -d '' library; do
    found=$((found + 1))
    newest=$(objdump -T "$library" | grep -o 'GLIBC_[0-9][0-9.]*' | sed 's/^GLIBC_//' | sort -uV | tail -1 || true)
    if [[ -n "$newest" && "$(printf '%s\n%s\n' "$newest" "$MAX_GLIBC" | sort -V | tail -1)" != "$MAX_GLIBC" ]]; then
        echo "::error::$library needs glibc $newest: at most $MAX_GLIBC"
        failed=1
    fi
    if readelf -d "$library" | grep -q 'NEEDED.*libstdc++'; then
        echo "::error::$library loads libstdc++: it is linked inside the libraries"
        failed=1
    fi
    echo "$library: glibc ${newest:-none}"
done < <(find "${directories[@]}" -path '*/native/linux-*' -name '*.so' -print0 2>/dev/null)

if [[ $found -eq 0 ]]; then
    echo "::error::no JNI library of Linux in ${directories[*]}"
    exit 1
fi
exit $failed
