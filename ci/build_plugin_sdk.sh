#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
PIN="$ROOT/ci/plugin-sdk.json"
REPOSITORY=$(jq -er .repository "$PIN")
COMMIT=$(jq -er .commit "$PIN")
TREE=$(jq -er .tree "$PIN")
SHA=$(jq -er .release_sha256 "$PIN")
SHORT=$(printf '%s' "$COMMIT" | cut -c1-12)
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
[ -z "$(git status --porcelain --untracked-files=no)" ] || {
    echo "Plugin SDK checkout contains tracked changes" >&2; exit 1;
}
git fetch --quiet --depth=1 origin "$COMMIT"
git -c core.autocrlf=false checkout --quiet --detach "$COMMIT"
[ "$(git rev-parse HEAD)" = "$COMMIT" ] || {
    echo "Unexpected plugin SDK source revision" >&2; exit 1;
}
[ "$(git rev-parse 'HEAD^{tree}')" = "$TREE" ] || {
    echo "Unexpected plugin SDK source tree" >&2; exit 1;
}
chmod +x gradlew
./gradlew assembleRelease --no-daemon --max-workers=2 \
    -Pkotlin.compiler.execution.strategy=in-process --console=plain
printf '%s  %s\n' "$SHA" build/outputs/aar/weartak-plugin-sdk-release.aar | sha256sum -c -
printf 'Verified plugin SDK %s: %s\n' "$COMMIT" "$SHA"
