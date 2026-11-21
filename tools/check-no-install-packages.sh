#!/usr/bin/env bash
# Reject APKs requesting sideload-install privileges, including from merged manifests.
set -euo pipefail

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk" ]]; then
    echo "ANDROID_HOME or ANDROID_SDK_ROOT is required" >&2
    exit 1
fi
aapt2="$sdk/build-tools/37.0.0/aapt2"
if [[ ! -x "$aapt2" ]]; then
    echo "aapt2 not found: $aapt2" >&2
    exit 1
fi
if [[ "$#" -lt 1 ]]; then
    echo "Usage: $0 path/to/app.apk [path/to/another.apk ...]" >&2
    exit 1
fi

for apk in "$@"; do
    permissions=$("$aapt2" dump permissions "$apk")
    if grep -Fq "android.permission.REQUEST_INSTALL_PACKAGES" <<< "$permissions"; then
        echo "Prohibited REQUEST_INSTALL_PACKAGES found in $apk" >&2
        exit 1
    fi
    echo "OK: REQUEST_INSTALL_PACKAGES is absent from $apk"
done
