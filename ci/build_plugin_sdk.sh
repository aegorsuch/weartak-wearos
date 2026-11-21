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
for name in $(env | sed -n 's/^\(GIT_[A-Za-z0-9_]*\)=.*/\1/p'); do
    case "$name" in
        GIT_SSL_CAINFO|GIT_SSL_CAPATH) ;;
        *) unset "$name" ;;
    esac
done
configure_unauthenticated_git() {
    export GIT_CONFIG_COUNT=4
    export GIT_CONFIG_NOSYSTEM=1
    export GIT_CONFIG_GLOBAL=/dev/null
    export GIT_TERMINAL_PROMPT=0
    export GIT_CONFIG_KEY_0=credential.helper
    export GIT_CONFIG_VALUE_0=
    export GIT_CONFIG_KEY_1=http.extraHeader
    export GIT_CONFIG_VALUE_1=
    export GIT_CONFIG_KEY_2=http.followRedirects
    export GIT_CONFIG_VALUE_2=false
    export GIT_CONFIG_KEY_3=core.hooksPath
    export GIT_CONFIG_VALUE_3=/dev/null
}
case "$REPOSITORY" in
    https://github.com/*)
        job_token=
        unset CI_JOB_TOKEN
        ;;
    https://git.tak.gov/weartak-plugins/weartak-plugin-sdk.git)
        [ -n "${CI_JOB_TOKEN:-}" ] || {
            echo "TAK.gov SDK fetch requires CI_JOB_TOKEN" >&2; exit 1;
        }
        job_token=$CI_JOB_TOKEN
        unset CI_JOB_TOKEN
        ;;
    *)
        echo "Unsupported plugin SDK source host" >&2
        exit 1
        ;;
esac
configure_unauthenticated_git
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
if [ -n "$job_token" ]; then
    export GIT_CONFIG_COUNT=5
    export GIT_CONFIG_KEY_4=http.https://git.tak.gov/weartak-plugins/weartak-plugin-sdk.git/.extraHeader
    credentials=$(printf '%s' "gitlab-ci-token:$job_token" | base64 | tr -d '\n')
    [ -n "$credentials" ] || {
        echo "Unable to prepare GitLab job-token authentication" >&2; exit 1;
    }
    export GIT_CONFIG_VALUE_4="Authorization: Basic $credentials"
    [ "$(git config --get-urlmatch http.extraHeader "$REPOSITORY/info/refs")" = "$GIT_CONFIG_VALUE_4" ] || {
        echo "Plugin SDK fetch failed: scoped Git authorization configuration mismatch." >&2; exit 1;
    }
fi
if ! fetch_error=$(git fetch --quiet --depth=1 origin "$COMMIT" 2>&1); then
    case "$fetch_error" in
        *"couldn't find remote ref"*|*"not our ref"*|*"unadvertised object"*)
            echo "Plugin SDK fetch failed: requested commit is unavailable." >&2 ;;
        *"Authentication failed"*|*"403"*|*"401"*|*"access denied"*)
            echo "Plugin SDK fetch failed: authentication or authorization rejected." >&2 ;;
        *"404"*|*"not found"*)
            echo "Plugin SDK fetch failed: repository or ref not found." >&2 ;;
        *"bad config"*|*"invalid key"*|*"missing config"*|*"invalid config"*)
            echo "Plugin SDK fetch failed: invalid Git configuration." >&2 ;;
        *"dubious ownership"*|*"unsafe repository"*)
            echo "Plugin SDK fetch failed: Git rejected repository ownership." >&2 ;;
        *"SSL"*|*"certificate"*|*"TLS"*)
            echo "Plugin SDK fetch failed: TLS verification or negotiation error." >&2 ;;
        *"resolve host"*|*"connect to"*|*"timed out"*)
            echo "Plugin SDK fetch failed: network connection error." >&2 ;;
        *"redirect"*)
            echo "Plugin SDK fetch failed: redirect refused." >&2 ;;
        *"not a git repository"*|*"does not appear to be a git repository"*)
            echo "Plugin SDK fetch failed: Git repository unavailable." >&2 ;;
        *"could not read Username"*|*"terminal prompts disabled"*)
            echo "Plugin SDK fetch failed: Git requested credentials." >&2 ;;
        *"could not read Password"*)
            echo "Plugin SDK fetch failed: Git requested a password." >&2 ;;
        *"protocol"*)
            echo "Plugin SDK fetch failed: unsupported or blocked protocol." >&2 ;;
        *)
            echo "Plugin SDK fetch failed: Git reported an unclassified error." >&2 ;;
    esac
    unset fetch_error
    echo "Plugin SDK source fetch failed; verify pinned ref and job-token read settings." >&2
    exit 1
fi
unset fetch_error
# Keep the ephemeral token out of Git hooks, checkout processing and Gradle.
unset CI_JOB_TOKEN job_token GIT_CONFIG_COUNT GIT_CONFIG_NOSYSTEM GIT_CONFIG_GLOBAL GIT_TERMINAL_PROMPT
for name in $(env | sed -n 's/^\(GIT_CONFIG_[A-Za-z0-9_]*\)=.*/\1/p'); do
    unset "$name"
done
unset credentials
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
