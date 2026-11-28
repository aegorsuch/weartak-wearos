"""Build and verify the immutable, source-pinned plugin SDK before host Gradle."""

import argparse
import base64
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import platform as platform_info
import re
import subprocess
import sys
import tempfile
from urllib.parse import urlsplit
import zipfile


ROOT = Path(__file__).resolve().parents[1]
TAK_GOV_SDK_REPOSITORY = (
    "https://git.tak.gov/weartak-plugins/weartak-plugin-sdk.git"
)
WEARTAK_373_SDK_COMMIT = "e9db0e6d88f2bcb23af90fa74e4b01fcc49c4dbd"
WEARTAK_373_SDK_TREE = "fdc25c49c98a84e97b91a4fef1992386bf6223b9"
GIT_ENVIRONMENT_KEYS = (
    "PATH", "LANG", "LC_ALL", "TMPDIR", "TMP", "TEMP", "SystemRoot",
    "SSL_CERT_FILE", "SSL_CERT_DIR", "GIT_SSL_CAINFO", "GIT_SSL_CAPATH",
    "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "http_proxy", "https_proxy", "no_proxy",
)


def run(*args, cwd, env=None):
    child_env = credential_free_environment() if env is None else env
    return subprocess.check_output(args, cwd=cwd, env=child_env, text=True).strip()


def validate_repository(repository):
    try:
        parsed = urlsplit(repository)
        port = parsed.port
    except ValueError as error:
        raise RuntimeError("Invalid plugin SDK repository URL") from error
    if (
        parsed.scheme != "https"
        or parsed.hostname not in ("github.com", "git.tak.gov")
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
        or port not in (None, 443)
    ):
        raise RuntimeError(
            "Plugin SDK source must use credential-free HTTPS on github.com or git.tak.gov"
        )
    if parsed.hostname == "git.tak.gov" and repository != TAK_GOV_SDK_REPOSITORY:
        raise RuntimeError("Only the canonical TAK.gov plugin SDK repository is allowed")
    return parsed


def git_fetch_environment(repository, isolated_home, source_env=None):
    source_env = os.environ if source_env is None else source_env
    parsed = validate_repository(repository)
    env = {key: source_env[key] for key in GIT_ENVIRONMENT_KEYS if key in source_env}
    settings = [
        ("credential.helper", ""),
        ("http.extraHeader", ""),
        ("http.followRedirects", "false"),
        ("core.hooksPath", os.devnull),
    ]
    if parsed.hostname == "git.tak.gov":
        token = source_env.get("CI_JOB_TOKEN")
        if not token:
            raise RuntimeError(
                "TAK.gov SDK fetch requires CI_JOB_TOKEN and target-project read access"
            )
        credentials = base64.b64encode(
            f"gitlab-ci-token:{token}".encode("utf-8")
        ).decode("ascii")
        settings.append(
            (
                f"http.{TAK_GOV_SDK_REPOSITORY}/.extraHeader",
                f"Authorization: Basic {credentials}",
            )
        )
    env.update(
        HOME=str(isolated_home),
        XDG_CONFIG_HOME=str(isolated_home),
        GIT_CONFIG_PARAMETERS=" ".join(
            "'" + f"{key}={value}".replace("'", "'\\''") + "'"
            for key, value in settings
        ),
        GIT_CONFIG_NOSYSTEM="1",
        GIT_CONFIG_GLOBAL=os.devnull,
        GIT_TERMINAL_PROMPT="0",
    )
    return env


def credential_free_environment(source_env=None):
    env = dict(os.environ if source_env is None else source_env)
    env.pop("CI_JOB_TOKEN", None)
    for key in tuple(env):
        if key.startswith("GIT_CONFIG_"):
            del env[key]
    return env


def sanitize_git_stderr(stderr, token=None):
    if isinstance(stderr, bytes):
        text = stderr.decode("utf-8", errors="replace")
    else:
        text = str(stderr or "")
    if token:
        text = text.replace(token, "[REDACTED]")
        encoded_token = base64.b64encode(
            f"gitlab-ci-token:{token}".encode("utf-8")
        ).decode("ascii")
        text = text.replace(encoded_token, "[REDACTED]")
    text = re.sub(
        r"(?i)\b(?:proxy-)?authorization\s*:\s*[^\r\n]*",
        "Authorization: [REDACTED]",
        text,
    )

    def redact_url_userinfo(match):
        authority = match.group(2)
        if "@" not in authority:
            return match.group(0)
        host = authority.rsplit("@", 1)[1]
        return f"{match.group(1)}://[REDACTED]@{host}"

    text = re.sub(
        r"(?i)\b(https?|socks(?:4a?|5h?)?)://([^/?#\s]+)",
        redact_url_userinfo,
        text,
    )
    text = re.sub(
        r"(?i)(?<![A-Za-z0-9+./:?=#-])([^/@\s/?#]+:[^/?#\s]*@)"
        r"([^/?#\s,;|)\]}]+)",
        lambda match: "[REDACTED]@" + match.group(2),
        text,
    )
    text = re.sub(
        r"(?i)([?&](?:access_token|token|private_token|job_token|password|secret)=)"
        r"[^&#\s]+",
        r"\1[REDACTED]",
        text,
    )
    text = " ".join(text.split())[:1000]
    return text or "[no stderr output]"


def fetch_source(checkout, repository, ref, local_source=None):
    if local_source is None:
        auth_repository = repository
        source = "origin"
    else:
        validate_repository(repository)
        source = str(local_source.resolve(strict=True))
        if not Path(source).is_dir():
            raise RuntimeError("Local plugin SDK source must be a directory")
        auth_repository = "https://github.com/aegorsuch/weartak-plugin-sdk.git"
    with tempfile.TemporaryDirectory(prefix="weartak-sdk-git-home-") as isolated_home:
        env = git_fetch_environment(auth_repository, isolated_home)
        result = subprocess.run(
            ["git", "fetch", "--quiet", "--depth=1", "--no-tags", "--", source, ref],
            cwd=checkout,
            env=env,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            check=False,
        )
    if result.returncode:
        if local_source is not None:
            raise RuntimeError("Pinned plugin SDK commit unavailable in local source")
        token = (
            os.environ.get("CI_JOB_TOKEN")
            if repository == TAK_GOV_SDK_REPOSITORY else None
        )
        git_version = run("git", "--version", cwd=checkout)
        details = sanitize_git_stderr(result.stderr, token)
        raise RuntimeError(
            f"Plugin SDK source fetch failed ({git_version}; "
            f"exit {result.returncode}): {details}"
        )


def verify_hash(path, expected):
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != expected:
        raise RuntimeError(f"Plugin SDK hash mismatch: expected {expected}, got {actual}")
    return actual


def validate_candidate_context(platform_name, environment):
    if platform_name != "windows":
        raise RuntimeError("Windows candidate checksum mode is Windows-only")
    if (
        environment.get("CI") != "true"
        or environment.get("CI_SERVER_HOST") != "git.tak.gov"
        or environment.get("CI_PROJECT_ID") != "6739"
        or environment.get("CI_PROJECT_PATH") != "core/weartak-core/wearos-tak-civ"
        or environment.get("CI_COMMIT_BRANCH") != "main"
        or environment.get("CI_COMMIT_REF_PROTECTED") != "true"
        or environment.get("CI_PIPELINE_SOURCE") not in ("push", "web")
    ):
        raise RuntimeError(
            "Windows candidate checksum requires the protected Core main pipeline"
        )
    if not environment.get("CI_JOB_ID") or not environment.get("CI_PIPELINE_ID"):
        raise RuntimeError("Windows candidate checksum requires CI job and pipeline IDs")


def build_arguments(wrapper, candidate=False):
    arguments = [
        str(wrapper), "assembleRelease", "--no-daemon", "--max-workers=2",
        "-Pkotlin.compiler.execution.strategy=in-process", "--console=plain",
    ]
    if candidate:
        arguments.extend(("--rerun-tasks", "--no-build-cache", "--no-configuration-cache"))
    return arguments


def verify_checkout_clean(checkout):
    has_head = subprocess.run(
        ["git", "rev-parse", "--verify", "--quiet", "HEAD"], cwd=checkout,
        env=credential_free_environment(), stdout=subprocess.DEVNULL,
    ).returncode == 0
    if has_head and subprocess.run(
        ["git", "-c", "core.autocrlf=false", "diff", "--quiet", "HEAD"], cwd=checkout,
        env=credential_free_environment(),
    ).returncode:
        raise RuntimeError("Plugin SDK checkout contains tracked changes")


def restore_git_blob_bytes(checkout, tracked):
    requests = "".join(f"HEAD:{name}\n" for name in sorted(tracked)).encode("utf-8")
    response = subprocess.run(
        ["git", "cat-file", "--batch"], cwd=checkout, input=requests,
        env=credential_free_environment(), stdout=subprocess.PIPE, check=True,
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
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--candidate-hash",
        action="store_true",
        help="Emit a non-publishing WEARTAK-373 Windows candidate checksum from protected CI",
    )
    parser.add_argument(
        "--local-source", type=Path,
        help="Fetch the pinned commit from an independently authenticated local Git checkout",
    )
    args = parser.parse_args()
    if args.local_source is not None and os.environ.get("CI"):
        raise RuntimeError("--local-source is only supported outside CI")
    pin = json.loads((ROOT / "ci" / "plugin-sdk.json").read_text(encoding="utf-8"))
    platform = "windows" if os.name == "nt" else sys.platform
    if args.candidate_hash:
        validate_candidate_context(platform, os.environ)
        if args.local_source is not None:
            raise RuntimeError("Windows candidate checksum mode does not accept local sources")
        pin["commit"] = WEARTAK_373_SDK_COMMIT
        pin["tree"] = WEARTAK_373_SDK_TREE
        expected = None
    else:
        expected = pin["release_sha256"].get(platform)
    if not args.candidate_hash and not expected:
        raise RuntimeError(f"No verified combined plugin SDK artifact hash for {platform}")
    overlay = pin["overlay"]
    identity = f"{pin['commit'][:12]}-{overlay['source_sha256'][:12]}"
    candidate_checkout = (
        tempfile.TemporaryDirectory(prefix="weartak-373-sdk-candidate-")
        if args.candidate_hash else None
    )
    checkout = (
        Path(candidate_checkout.name) / identity
        if candidate_checkout is not None
        else ROOT / ".gradle" / "sdk" / identity
    )
    checkout.mkdir(parents=True, exist_ok=candidate_checkout is None)
    if not (checkout / ".git").exists():
        run("git", "init", "--quiet", cwd=checkout)
        run("git", "remote", "add", "origin", pin["repository"], cwd=checkout)
    if run("git", "remote", "get-url", "origin", cwd=checkout) != pin["repository"]:
        raise RuntimeError("Unexpected plugin SDK source remote")
    verify_checkout_clean(checkout)
    fetch_source(checkout, pin["repository"], pin["commit"], args.local_source)
    run(
        "git",
        "-c",
        "core.autocrlf=false",
        "checkout",
        "--quiet",
        "--detach",
        pin["commit"],
        cwd=checkout,
        env=credential_free_environment(),
    )
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
    subprocess.run(
        build_arguments(wrapper, candidate=args.candidate_hash),
        cwd=checkout,
        env=credential_free_environment(),
        check=True,
    )
    aar = checkout / "build" / "outputs" / "aar" / "weartak-plugin-sdk-release.aar"
    if platform == "linux":
        subprocess.run(["java", str(ROOT / "ci" / "NormalizeSdkAarModes.java"), str(aar)],
                       cwd=checkout, env=credential_free_environment(), check=True)
    actual = hashlib.sha256(aar.read_bytes()).hexdigest()
    if args.candidate_hash:
        inventory_entries = [
            line.split("  ", 1) for line in
            (ROOT / overlay["inventory"]).read_text(encoding="ascii").splitlines()
        ]
        java_version = subprocess.run(
            ["java", "-version"],
            cwd=checkout,
            env=credential_free_environment(),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            check=True,
        ).stdout.splitlines()[0]
        report = {
            "candidate_only": True,
            "publishing": False,
            "platform": platform,
            "os_version": platform_info.platform(),
            "ci_job_id": os.environ["CI_JOB_ID"],
            "ci_pipeline_id": os.environ["CI_PIPELINE_ID"],
            "ci_pipeline_url": os.environ.get("CI_PIPELINE_URL"),
            "ci_pipeline_source": os.environ.get("CI_PIPELINE_SOURCE"),
            "core_builder_commit": os.environ["CI_COMMIT_SHA"],
            "core_builder_tree": run("git", "rev-parse", "HEAD^{tree}", cwd=ROOT),
            "runner_id": os.environ.get("CI_RUNNER_ID"),
            "runner_arch": os.environ.get("CI_RUNNER_EXECUTABLE_ARCH"),
            "python_version": platform_info.python_version(),
            "java_version": java_version,
            "sdk_repository": pin["repository"],
            "sdk_commit": pin["commit"],
            "sdk_tree": pin["tree"],
            "overlay_archive_sha256": hashlib.sha256(
                (ROOT / overlay["archive"]).read_bytes()
            ).hexdigest(),
            "overlay_inventory_sha256": hashlib.sha256(
                (ROOT / overlay["inventory"]).read_bytes()
            ).hexdigest(),
            "overlay_sources": [
                {"path": path, "sha256": digest} for digest, path in inventory_entries
            ],
            "aar_sha256": actual,
        }
        report_path = ROOT / "plugin-sdk-windows-candidate.json"
        if report_path.is_symlink():
            raise RuntimeError("Windows candidate report cannot be a symbolic link")
        report_path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        print(json.dumps(report, sort_keys=True))
    else:
        if actual != expected:
            raise RuntimeError(f"Plugin SDK hash mismatch: expected {expected}, got {actual}")
        print(f"Verified plugin SDK {pin['commit']} + overlay {overlay['source_sha256']} ({platform}): {expected}")
    print(f"AAR: {aar}")


if __name__ == "__main__":
    main()
