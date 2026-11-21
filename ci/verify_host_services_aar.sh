#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
COMMIT=$(jq -er .commit ci/plugin-sdk.json)
OVERLAY=$(jq -er .overlay.source_sha256 ci/plugin-sdk.json)
SHA=$(jq -er .release_sha256.linux ci/plugin-sdk.json)
SDK=".gradle/sdk/$(printf '%s' "$COMMIT" | cut -c1-12)-$(printf '%s' "$OVERLAY" | cut -c1-12)/build/outputs/aar/weartak-plugin-sdk-release.aar"
HOST=weartak-plugin-host-services-sdk/build/outputs/aar/weartak-plugin-host-services-sdk-release.aar
rm -f weartak-plugin-host-services-sdk/build/outputs/aar/verified-classes.sha256
printf '%s  %s\n' "$SHA" "$SDK" | sha256sum -c -
java ci/VerifyHostServicesAar.java "$SDK" "$HOST"
