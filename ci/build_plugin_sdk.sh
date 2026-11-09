#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
PIN="$ROOT/ci/plugin-sdk.json"
REPOSITORY=$(jq -er .repository "$PIN")
COMMIT=$(jq -er .commit "$PIN")
TREE=$(jq -er .tree "$PIN")
SHA=$(jq -er .release_sha256.linux "$PIN") || {
    echo "No verified combined plugin SDK artifact hash for Linux" >&2; exit 1;
}
ARCHIVE="$ROOT/$(jq -er .overlay.archive "$PIN")"
ARCHIVE_SHA=$(jq -er .overlay.archive_sha256 "$PIN")
INVENTORY="$ROOT/$(jq -er .overlay.inventory "$PIN")"
OVERLAY_SHA=$(jq -er .overlay.source_sha256 "$PIN")
[ "$(uname -s)" = Linux ] || {
    echo "Use the Python helper on Windows; no hash is verified for this platform" >&2; exit 1;
}
SHORT="$(printf '%s' "$COMMIT" | cut -c1-12)-$(printf '%s' "$OVERLAY_SHA" | cut -c1-12)"
CHECKOUT="$ROOT/.gradle/sdk/$SHORT"
mkdir -p "$CHECKOUT"
cd "$CHECKOUT"
if [ ! -d .git ]; then
    git init --quiet
    git remote add origin "$REPOSITORY"
fi
[ "$(git remote get-url origin)" = "$REPOSITORY" ] || {
    echo "Unexpected plugin SDK source remote" >&2; exit 1;
}
if git rev-parse --verify --quiet HEAD >/dev/null; then
    git -c core.autocrlf=false diff --quiet HEAD || {
        echo "Plugin SDK checkout contains tracked changes" >&2; exit 1;
    }
fi
git fetch --quiet --depth=1 origin "$COMMIT"
git -c core.autocrlf=false checkout --quiet --detach "$COMMIT"
[ "$(git rev-parse HEAD)" = "$COMMIT" ] || {
    echo "Unexpected plugin SDK source revision" >&2; exit 1;
}
[ "$(git rev-parse 'HEAD^{tree}')" = "$TREE" ] || {
    echo "Unexpected plugin SDK source tree" >&2; exit 1;
}
git ls-files | while IFS= read -r path; do
    git cat-file blob "HEAD:$path" > "$path"
done
printf '%s  %s\n' "$ARCHIVE_SHA" "$ARCHIVE" | sha256sum -c -
printf '%s  %s\n' "$OVERLAY_SHA" "$INVENTORY" | sha256sum -c -
EXPECTED_FILES=$(cut -d ' ' -f3- "$INVENTORY" | LC_ALL=C sort)
[ "$(jar tf "$ARCHIVE" | LC_ALL=C sort)" = "$EXPECTED_FILES" ] || {
    echo "Plugin SDK overlay does not match pinned inventory" >&2; exit 1;
}
printf '%s\n' "$EXPECTED_FILES" | while IFS= read -r path; do
    case "$path" in
        *../*|/*|*\\*) echo "Invalid plugin SDK overlay path" >&2; exit 1 ;;
        src/main/aidl/com/weartak/plugin/sdk/transport/*|src/main/java/com/weartak/plugin/sdk/transport/*|src/test/java/com/weartak/plugin/sdk/transport/*) ;;
        *) echo "Plugin SDK overlay may only add transport sources" >&2; exit 1 ;;
    esac
    [ -z "$(git ls-files -- "$path")" ] || {
        echo "Plugin SDK overlay cannot replace canonical files" >&2; exit 1;
    }
    if [ -e "$path" ]; then
        grep -F "  $path" "$INVENTORY" | sha256sum -c -
    fi
done
jar xf "$ARCHIVE"
sha256sum -c "$INVENTORY"
[ "$(git ls-files --others --exclude-standard | LC_ALL=C sort)" = "$EXPECTED_FILES" ] || {
    echo "Unexpected files in combined plugin SDK checkout" >&2; exit 1;
}
chmod +x gradlew
./gradlew assembleRelease --no-daemon --max-workers=2 \
    -Pkotlin.compiler.execution.strategy=in-process --console=plain
sha256sum build/outputs/aar/weartak-plugin-sdk-release.aar
printf '%s  %s\n' "$SHA" build/outputs/aar/weartak-plugin-sdk-release.aar | sha256sum -c -
printf 'Verified plugin SDK %s + overlay %s: %s\n' "$COMMIT" "$OVERLAY_SHA" "$SHA"
