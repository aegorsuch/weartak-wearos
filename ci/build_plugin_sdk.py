"""Build and verify the immutable, source-pinned plugin SDK before host Gradle."""

import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import subprocess
import sys
import zipfile


ROOT = Path(__file__).resolve().parents[1]


def run(*args, cwd):
    return subprocess.check_output(args, cwd=cwd, text=True).strip()


def verify_hash(path, expected):
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != expected:
        raise RuntimeError(f"Plugin SDK hash mismatch: expected {expected}, got {actual}")


def verify_checkout_clean(checkout):
    has_head = subprocess.run(
        ["git", "rev-parse", "--verify", "--quiet", "HEAD"], cwd=checkout,
        stdout=subprocess.DEVNULL,
    ).returncode == 0
    if has_head and subprocess.run(
        ["git", "-c", "core.autocrlf=false", "diff", "--quiet", "HEAD"], cwd=checkout,
    ).returncode:
        raise RuntimeError("Plugin SDK checkout contains tracked changes")


def restore_git_blob_bytes(checkout, tracked):
    requests = "".join(f"HEAD:{name}\n" for name in sorted(tracked)).encode("utf-8")
    response = subprocess.run(
        ["git", "cat-file", "--batch"], cwd=checkout, input=requests,
        stdout=subprocess.PIPE, check=True,
    )
    blobs = io.BytesIO(response.stdout)
    for name in sorted(tracked):
        header = blobs.readline().split()
        if len(header) != 3 or header[1] != b"blob":
            raise RuntimeError(f"Plugin SDK canonical source is not a blob: {name}")
        contents = blobs.read(int(header[2]))
        if blobs.read(1) != b"\n":
            raise RuntimeError("Invalid plugin SDK canonical blob response")
        (checkout / name).write_bytes(contents)


def apply_overlay(checkout, overlay, root=ROOT):
    archive = root / overlay["archive"]
    inventory = root / overlay["inventory"]
    verify_hash(archive, overlay["archive_sha256"])
    verify_hash(inventory, overlay["source_sha256"])
    entries = {}
    for line in inventory.read_text(encoding="ascii").splitlines():
        expected, name = line.split("  ", 1)
        path = PurePosixPath(name)
        if path.is_absolute() or ".." in path.parts or "\\" in name or name in entries:
            raise RuntimeError("Invalid plugin SDK overlay path")
        if not name.startswith((
            "src/main/aidl/com/weartak/plugin/sdk/transport/",
            "src/main/java/com/weartak/plugin/sdk/transport/",
            "src/test/java/com/weartak/plugin/sdk/transport/",
        )):
            raise RuntimeError("Plugin SDK overlay may only add transport sources")
        entries[name] = expected
    with zipfile.ZipFile(archive) as source:
        if len(source.namelist()) != len(entries) or set(source.namelist()) != set(entries):
            raise RuntimeError("Plugin SDK overlay does not match pinned inventory")
        for name, expected in entries.items():
            contents = source.read(name)
            if hashlib.sha256(contents).hexdigest() != expected:
                raise RuntimeError(f"Plugin SDK overlay source hash mismatch: {name}")
            target = checkout / name
            if target.exists():
                verify_hash(target, expected)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(contents)
    return set(entries)


def main():
    pin = json.loads((ROOT / "ci" / "plugin-sdk.json").read_text(encoding="utf-8"))
    platform = "windows" if os.name == "nt" else sys.platform
    expected = pin["release_sha256"].get(platform)
    if not expected:
        raise RuntimeError(f"No verified combined plugin SDK artifact hash for {platform}")
    overlay = pin["overlay"]
    identity = f"{pin['commit'][:12]}-{overlay['source_sha256'][:12]}"
    checkout = ROOT / ".gradle" / "sdk" / identity
    checkout.mkdir(parents=True, exist_ok=True)
    if not (checkout / ".git").exists():
        run("git", "init", "--quiet", cwd=checkout)
        run("git", "remote", "add", "origin", pin["repository"], cwd=checkout)
    if run("git", "remote", "get-url", "origin", cwd=checkout) != pin["repository"]:
        raise RuntimeError("Unexpected plugin SDK source remote")
    verify_checkout_clean(checkout)
    run("git", "fetch", "--quiet", "--depth=1", "origin", pin["commit"], cwd=checkout)
    run("git", "-c", "core.autocrlf=false", "checkout", "--quiet", "--detach",
        pin["commit"], cwd=checkout)
    if run("git", "rev-parse", "HEAD", cwd=checkout) != pin["commit"]:
        raise RuntimeError("Unexpected plugin SDK source revision")
    if run("git", "rev-parse", "HEAD^{tree}", cwd=checkout) != pin["tree"]:
        raise RuntimeError("Unexpected plugin SDK source tree")
    tracked = set(run("git", "ls-files", cwd=checkout).splitlines())
    restore_git_blob_bytes(checkout, tracked)
    added = apply_overlay(checkout, overlay)
    if tracked & added:
        raise RuntimeError("Plugin SDK overlay cannot replace canonical files")
    untracked = set(run("git", "ls-files", "--others", "--exclude-standard", cwd=checkout).splitlines())
    if untracked != added:
        raise RuntimeError("Unexpected files in combined plugin SDK checkout")
    wrapper = checkout / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if os.name != "nt":
        wrapper.chmod(wrapper.stat().st_mode | 0o100)
    subprocess.run([str(wrapper), "assembleRelease", "--no-daemon", "--max-workers=2",
                    "-Pkotlin.compiler.execution.strategy=in-process", "--console=plain"],
                   cwd=checkout, check=True)
    aar = checkout / "build" / "outputs" / "aar" / "weartak-plugin-sdk-release.aar"
    verify_hash(aar, expected)
    print(f"Verified plugin SDK {pin['commit']} + overlay {overlay['source_sha256']} ({platform}): {expected}")
    print(f"AAR: {aar}")


if __name__ == "__main__":
    main()
