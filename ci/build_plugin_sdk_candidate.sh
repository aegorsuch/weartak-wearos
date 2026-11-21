#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
CANDIDATE="$ROOT/.gradle/sdk-candidate-probe"
COMMIT=35b294158c60ab455eb27ad7e69407efc6a6affc
TREE=6b6f54f99266a1a172e3e0b04a8f74402cbf54a9
REPOSITORY=https://git.tak.gov/weartak-plugins/weartak-plugin-sdk.git

[ -n "${CI_JOB_TOKEN:-}" ] || { echo "Missing CI job token" >&2; exit 1; }
job_token=$CI_JOB_TOKEN
unset CI_JOB_TOKEN
[ ! -e "$CANDIDATE" ] || { echo "Candidate directory already exists" >&2; exit 1; }
mkdir -p "$CANDIDATE/ci"
cp -R "$ROOT/ci/plugin-sdk-transport" "$CANDIDATE/ci/"
jq --arg repo "$REPOSITORY" --arg commit "$COMMIT" --arg tree "$TREE" \
    '.repository=$repo | .commit=$commit | .tree=$tree | .release_sha256.linux=("0" * 64)' \
    "$ROOT/ci/plugin-sdk.json" > "$CANDIDATE/ci/plugin-sdk.json"

CHECK='printf '"'"'%s  %s\n'"'"' "$SHA" build/outputs/aar/weartak-plugin-sdk-release.aar | sha256sum -c -'
RESULT='printf '"'"'Verified plugin SDK %s + overlay %s: %s\n'"'"' "$COMMIT" "$OVERLAY_SHA" "$SHA"'
[ "$(grep -Fxc "$CHECK" "$ROOT/ci/build_plugin_sdk.sh")" = 1 ] || {
    echo "Unexpected builder hash check" >&2; exit 1;
}
[ "$(grep -Fxc "$RESULT" "$ROOT/ci/build_plugin_sdk.sh")" = 1 ] || {
    echo "Unexpected builder success line" >&2; exit 1;
}
[ "$(tail -n 2 "$ROOT/ci/build_plugin_sdk.sh")" = "$(printf '%s\n%s' "$CHECK" "$RESULT")" ] || {
    echo "Unexpected builder ending" >&2; exit 1;
}
sed '$d' "$ROOT/ci/build_plugin_sdk.sh" | sed '$d' > "$CANDIDATE/ci/build_plugin_sdk.sh"
CI_JOB_TOKEN=$job_token sh "$CANDIDATE/ci/build_plugin_sdk.sh"
unset job_token
echo "Candidate build completed; artifact hash above is not a production pin."
