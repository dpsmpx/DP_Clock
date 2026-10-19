#!/bin/sh
# Отладочный APK DP_Clock (D-005, docs/DEVELOPMENT/BUILD.md).
#
#   tools/debug-apk.sh             собрать build/debug-apk/debug-<коммит>.apk
#   tools/debug-apk.sh --publish   и выложить его новым коммитом в ветку debug-builds
#
# Собирается только закоммиченное дерево: короткий SHA в имени файла должен
# описывать то, что внутри APK. Переменная DEBUG_APK_TRAILERS — строки,
# которые дописываются в конец сообщения коммита сборки.
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BRANCH=debug-builds
PUBLISH=0
case "${1:-}" in
    "") ;;
    --publish) PUBLISH=1 ;;
    *) echo "✗ ключ $1: есть только --publish"; exit 2 ;;
esac

cd "$ROOT"
if [ -n "$(git status --porcelain)" ]; then
    echo "✗ есть незакоммиченные изменения: SHA в имени файла не описал бы APK"
    exit 2
fi
FULL_SHA="$(git rev-parse HEAD)"
NAME="debug-$(git rev-parse --short HEAD).apk"

./gradlew --console=plain :app:assembleDebug
mkdir -p build/debug-apk
APK="$ROOT/build/debug-apk/$NAME"
cp app/build/outputs/apk/debug/app-debug.apk "$APK"
SUM="$(sha256sum "$APK" | cut -d ' ' -f 1)"
echo "APK: $APK"
echo "SHA-256: $SUM"
[ "$PUBLISH" -eq 1 ] || exit 0

VERSION="$(sed -n 's/^ *versionName = "\(.*\)"$/\1/p' app/build.gradle.kts)"
WT="$(mktemp -d)"
trap 'git -C "$ROOT" worktree remove --force "$WT" 2>/dev/null || rm -rf "$WT"' EXIT

# Ветка есть на сервере — продолжить её; нет (код 2) — начать без истории main.
status=0
git ls-remote --exit-code --heads origin "$BRANCH" >/dev/null || status=$?
case "$status" in
    0)
        git fetch -q origin "$BRANCH"
        git worktree add -q -f -B "$BRANCH" "$WT" FETCH_HEAD
        ;;
    2)
        git worktree add -q -f --orphan -B "$BRANCH" "$WT"
        ;;
    *)
        echo "✗ сервер недоступен (git ls-remote, код $status)"
        exit 1
        ;;
esac

if [ -e "$WT/$NAME" ]; then
    echo "✓ $NAME уже в ветке $BRANCH"
    exit 0
fi
cp "$APK" "$WT/$NAME"
git -C "$WT" add "$NAME"
MESSAGE="$(printf '%s\n\nBuilt from %s (%s), version %s.\nSHA-256: %s\n' \
    "$NAME" "$FULL_SHA" "$(git log -1 --format=%s HEAD)" "$VERSION" "$SUM")"
if [ -n "${DEBUG_APK_TRAILERS:-}" ]; then
    MESSAGE="$(printf '%s\n\n%s\n' "$MESSAGE" "$DEBUG_APK_TRAILERS")"
fi
git -C "$WT" commit -q -m "$MESSAGE"

# Сеть бывает нестабильна: до четырёх повторов с паузами 2, 4, 8 и 16 секунд.
for pause in 2 4 8 16 none; do
    if git -C "$WT" push -q -u origin "$BRANCH"; then
        echo "✓ $NAME — в ветке $BRANCH"
        exit 0
    fi
    [ "$pause" = none ] && break
    sleep "$pause"
done
echo "✗ push в $BRANCH не удался"
exit 1
