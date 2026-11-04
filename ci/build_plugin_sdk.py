"""Build and verify the immutable, source-pinned plugin SDK before host Gradle."""

import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]


def run(*args, cwd):
    return subprocess.check_output(args, cwd=cwd, text=True).strip()


def verify_hash(path, expected):
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != expected:
        raise RuntimeError(f"Plugin SDK hash mismatch: expected {expected}, got {actual}")


def main():
    pin = json.loads((ROOT / "ci" / "plugin-sdk.json").read_text(encoding="utf-8"))
    checkout = ROOT / ".gradle" / "sdk" / pin["commit"][:12]
    checkout.mkdir(parents=True, exist_ok=True)
    if not (checkout / ".git").exists():
        run("git", "init", "--quiet", cwd=checkout)
        run("git", "remote", "add", "origin", pin["repository"], cwd=checkout)
    if run("git", "remote", "get-url", "origin", cwd=checkout) != pin["repository"]:
        raise RuntimeError("Unexpected plugin SDK source remote")
    if run("git", "status", "--porcelain", "--untracked-files=no", cwd=checkout):
        raise RuntimeError("Plugin SDK checkout contains tracked changes")
    run("git", "fetch", "--quiet", "--depth=1", "origin", pin["commit"], cwd=checkout)
    run("git", "-c", "core.autocrlf=false", "checkout", "--quiet", "--detach",
        pin["commit"], cwd=checkout)
    if run("git", "rev-parse", "HEAD", cwd=checkout) != pin["commit"]:
        raise RuntimeError("Unexpected plugin SDK source revision")
    if run("git", "rev-parse", "HEAD^{tree}", cwd=checkout) != pin["tree"]:
        raise RuntimeError("Unexpected plugin SDK source tree")
    wrapper = checkout / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if os.name != "nt":
        wrapper.chmod(wrapper.stat().st_mode | 0o100)
    subprocess.run([str(wrapper), "assembleRelease", "--no-daemon", "--max-workers=2",
                    "-Pkotlin.compiler.execution.strategy=in-process", "--console=plain"],
                   cwd=checkout, check=True)
    aar = checkout / "build" / "outputs" / "aar" / "weartak-plugin-sdk-release.aar"
    platform = "windows" if os.name == "nt" else sys.platform
    expected = pin["release_sha256"][platform]
    verify_hash(aar, expected)
    print(f"Verified plugin SDK {pin['commit']} ({platform}): {expected}")
    print(f"AAR: {aar}")


if __name__ == "__main__":
    main()
