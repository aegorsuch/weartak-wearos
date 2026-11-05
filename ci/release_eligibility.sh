#!/bin/sh
set -eu

# Fail closed on incomplete history; never infer docs-only from a partial range.
git rev-parse --verify "${CI_COMMIT_SHA:?}^{commit}" >/dev/null
test "$(git rev-parse HEAD)" = "$CI_COMMIT_SHA" || {
    echo "ERROR: release checkout differs from pipeline source." >&2
    exit 1
}
before="${CI_COMMIT_BEFORE_SHA:?}"
case "$before" in
    0000000000000000000000000000000000000000)
        echo release
        exit 0
        ;;
esac
git merge-base --is-ancestor "$before" "$CI_COMMIT_SHA" || {
    echo "ERROR: release change range is missing or not ancestral." >&2
    exit 1
}
changes="$(git -c core.quotePath=true diff --name-only "$before" "$CI_COMMIT_SHA")"
while IFS= read -r path; do
    case "$path" in
        README.md|ci/README.md|"") ;;
        *)
            echo release
            exit 0
            ;;
    esac
done <<EOF
$changes
EOF
echo docs-only
