"""Verify core CI can read the exact TAK.gov SDK source revision."""

import subprocess
import tempfile
from pathlib import Path

from build_plugin_sdk import (
    TAK_GOV_SDK_REPOSITORY,
    credential_free_environment,
    fetch_source,
)


SDK_REF = "refs/heads/aegorsuch-weartak-313-ditto-runtime-sdk"
SDK_COMMIT = "35b294158c60ab455eb27ad7e69407efc6a6affc"
SDK_TREE = "6b6f54f99266a1a172e3e0b04a8f74402cbf54a9"


def git(*args, cwd):
    result = subprocess.run(
        ["git", *args],
        cwd=cwd,
        env=credential_free_environment(),
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
        check=False,
    )
    if result.returncode:
        raise RuntimeError(
            f"Read-only SDK source verification failed (git exit {result.returncode})"
        )
    return result.stdout.strip()


def main():
    with tempfile.TemporaryDirectory(prefix="weartak-sdk-source-probe-") as directory:
        checkout = Path(directory)
        git("init", "--quiet", cwd=checkout)
        git("remote", "add", "origin", TAK_GOV_SDK_REPOSITORY, cwd=checkout)
        fetch_source(checkout, TAK_GOV_SDK_REPOSITORY, SDK_REF)
        commit = git("rev-parse", "FETCH_HEAD", cwd=checkout)
        tree = git("rev-parse", "FETCH_HEAD^{tree}", cwd=checkout)
        if commit != SDK_COMMIT or tree != SDK_TREE:
            raise RuntimeError(
                "TAK.gov SDK source ref did not resolve to the expected immutable commit/tree"
            )
        fetch_source(checkout, TAK_GOV_SDK_REPOSITORY, SDK_COMMIT)
        if git("rev-parse", "FETCH_HEAD", cwd=checkout) != SDK_COMMIT:
            raise RuntimeError("TAK.gov SDK commit fetch returned an unexpected revision")
    print(f"Verified read-only TAK.gov SDK source {commit} ({tree})")


if __name__ == "__main__":
    main()
