"""Validate the authoritative WearTAK version and upgrade order."""

import argparse
import os
from pathlib import Path
import re
import subprocess
import sys


MANIFEST = "wear-version.properties"
MAX_ANDROID_CODE = 2_100_000_000
MAX_BASE_CODE = (MAX_ANDROID_CODE - 9999) // 10000
VERSION_PATTERN = r"(?:0|[1-9][0-9]*)(?:\.(?:0|[1-9][0-9]*)){2,3}"


class VersionError(Exception):
    pass


def parse_manifest(text):
    values = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        match = re.fullmatch(r"(versionName|baseVersionCode)=([0-9.]+)", line)
        if not match or match[1] in values:
            raise VersionError("Version manifest has an invalid or duplicate property.")
        values[match[1]] = match[2]
    if set(values) != {"versionName", "baseVersionCode"}:
        raise VersionError("Version manifest requires versionName and baseVersionCode only.")
    name, raw_code = values["versionName"], values["baseVersionCode"]
    if not re.fullmatch(VERSION_PATTERN, name):
        raise VersionError("versionName must have three or four numeric components without leading zeros.")
    if not re.fullmatch(r"[1-9][0-9]{0,5}", raw_code):
        raise VersionError("baseVersionCode must be an integer in 1..209999.")
    code = int(raw_code)
    if code > MAX_BASE_CODE:
        raise VersionError("baseVersionCode must fit every IID 1..9999 below Android's 2100000000 limit.")
    return name, code


def read_manifest(root):
    return parse_manifest((root / MANIFEST).read_text(encoding="ascii"))


def validate_upgrade(previous, current):
    def components(name):
        parts = tuple(map(int, name.split(".")))
        return parts + (0,) * (4 - len(parts))

    old_name, old_code = previous
    name, code = current
    if components(name) < components(old_name) or code < old_code:
        raise VersionError("Host version and baseVersionCode must not decrease.")
    if components(name) > components(old_name) and code <= old_code:
        raise VersionError("A greater host version requires a strictly greater baseVersionCode.")


def git(root, *args):
    result = subprocess.run(
        ["git", *args], cwd=root, capture_output=True, text=True, check=False,
    )
    if result.returncode:
        raise VersionError("Cannot verify version history; a complete ancestral Git range is required.")
    return result.stdout.strip()


def version_at(root, sha):
    paths = git(root, "ls-tree", "--name-only", sha, "--", MANIFEST).splitlines()
    if MANIFEST in paths:
        return parse_manifest(git(root, "show", f"{sha}:{MANIFEST}"))
    # One-time migration baseline, never a fallback for a malformed manifest.
    build = git(root, "show", f"{sha}:app/build.gradle")
    name = re.search(r'def baseVersionName = "([0-9.]+)"', build)
    code = re.search(r"def baseVersionCode = ([0-9]+)", build)
    if not name or not code:
        raise VersionError("Historical source has neither a manifest nor legacy version definitions.")
    return parse_manifest(f"versionName={name[1]}\nbaseVersionCode={code[1]}\n")


def ci_base(root, env):
    source = env.get("CI_COMMIT_SHA", "")
    if not re.fullmatch(r"[0-9a-f]{40}", source) or git(root, "rev-parse", "HEAD") != source:
        raise VersionError("CI checkout must match its exact source SHA.")
    if env.get("CI_PIPELINE_SOURCE") == "merge_request_event":
        base = (env.get("CI_MERGE_REQUEST_TARGET_BRANCH_SHA")
                or env.get("CI_MERGE_REQUEST_DIFF_BASE_SHA"))
    else:
        base = env.get("CI_COMMIT_BEFORE_SHA")
        if not base or base == "0" * 40:
            if env.get("CI_COMMIT_BRANCH") == "main" and env.get("CI_PIPELINE_SOURCE") == "push":
                raise VersionError("Main version validation requires its previous push SHA.")
            if env.get("CI_COMMIT_TAG") or env.get("CI_COMMIT_BRANCH") == "main":
                base = git(root, "rev-parse", "HEAD^")
            else:
                base = git(root, "merge-base", "HEAD", "origin/main")
    if not base or not re.fullmatch(r"[0-9a-f]{40}", base):
        raise VersionError("Missing version comparison SHA.")
    git(root, "merge-base", "--is-ancestor", base, source)
    git(root, "diff", "--exit-code", "HEAD", "--", MANIFEST)
    return base


def validate(root, base):
    current = read_manifest(root)
    validate_upgrade(version_at(root, base), current)
    return current


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["validate"])
    parser.add_argument("--base", help="Local validation baseline (defaults to HEAD).")
    parser.add_argument("--ci", action="store_true", help="Validate the CI comparison range.")
    args = parser.parse_args(argv)
    root = Path(__file__).resolve().parents[1]
    try:
        base = ci_base(root, os.environ) if args.ci else (args.base or "HEAD")
        name, code = validate(root, base)
        print(f"WearTAK version {name}; base code {code}; pipeline IID range 1..9999 verified.")
    except (VersionError, OSError, UnicodeError, ValueError) as error:
        if isinstance(error, VersionError):
            print(f"ERROR: {error}", file=sys.stderr)
        else:
            print(f"ERROR: Version validation failed ({type(error).__name__}).", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
