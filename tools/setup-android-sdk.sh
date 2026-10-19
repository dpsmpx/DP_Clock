#!/bin/sh
# Ставит Android SDK для сборки DP_Clock (docs/DEVELOPMENT/BUILD.md):
# command-line tools, платформу и build-tools, на которые рассчитан
# app/build.gradle.kts, и пишет sdk.dir в local.properties.
#
#   tools/setup-android-sdk.sh      SDK — в $ANDROID_HOME или ~/android-sdk
#
# Уже установленное заново не скачивается. Запуск означает согласие с
# лицензиями Android SDK (sdkmanager --licenses).
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
# Архив и сумма — из https://dl.google.com/android/repository/repository2-3.xml.
TOOLS_ZIP=commandlinetools-linux-16111833_latest.zip
TOOLS_SHA1=e025545c62a8e64c7559119566a569fb1dec5f60
# compileSdk и build-tools — те же, что в app/build.gradle.kts; platform-tools
# AGP требует сам и иначе докачивает при первой сборке.
PACKAGES="platforms;android-37.0 build-tools;37.0.0 platform-tools"

SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' EXIT
    echo "Скачиваю $TOOLS_ZIP"
    curl -fsSL --retry 4 -o "$tmp/tools.zip" "https://dl.google.com/android/repository/$TOOLS_ZIP"
    echo "$TOOLS_SHA1  $tmp/tools.zip" | sha1sum -c -
    unzip -q "$tmp/tools.zip" -d "$tmp"
    mkdir -p "$SDK/cmdline-tools"
    rm -rf "$SDK/cmdline-tools/latest"
    mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
fi

yes | "$SDKMANAGER" --sdk_root="$SDK" --licenses >/dev/null
# shellcheck disable=SC2086 # имена пакетов разделены пробелами намеренно
"$SDKMANAGER" --sdk_root="$SDK" $PACKAGES

printf 'sdk.dir=%s\n' "$SDK" > "$ROOT/local.properties"
echo "Android SDK: $SDK (sdk.dir записан в local.properties)"
