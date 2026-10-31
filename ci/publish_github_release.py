"""Publish the verified canonical APK to the fixed personal GitHub mirror."""

import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request


PERSONAL_REPO = "aegorsuch/weartak-wearos"
TOKEN_VARIABLE = "WEARTAK_PERSONAL_GITHUB_TOKEN"
# Git tags cannot contain spaces or "/", so the tag is the title in tag-safe form.
TAG = "TAK.gov-Forge-CI-CD-Pipeline-Release"
TITLE = "TAK.gov Forge CI/CD Pipeline Release"
MARKER = "weartak-rolling-v1"
MANAGED_START = "<!-- weartak-release-managed:start -->"
MANAGED_END = "<!-- weartak-release-managed:end -->"
ASSET_PATTERN = re.compile(r"WearTAK-[0-9A-Za-z._-]*-p([1-9][0-9]*)\.apk")
LEGACY_ASSET_PATTERN = re.compile(r"weartak-development-p([1-9][0-9]*)-([0-9a-f]{64})\.apk")
LABEL_PREFIX = "weartak-rolling-v1:"
OUTPUT = Path("app/build/outputs/apk/release")


class PublishError(Exception):
    pass


class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if urllib.parse.urlsplit(newurl).scheme != "https":
            raise PublishError("Refusing non-HTTPS asset redirect.")
        redirect = super().redirect_request(req, fp, code, msg, headers, newurl)
        # Asset downloads can redirect to signed storage URLs. Never forward secrets.
        if redirect is not None:
            redirect.remove_header("Authorization")
        return redirect


class GitHub:
    def __init__(self, token):
        self.token = token
        self.repository_name = PERSONAL_REPO
        self.opener = urllib.request.build_opener(SafeRedirect())

    def request(self, method, path, data=None, *, binary=False, missing_ok=False):
        host = "https://uploads.github.com" if binary else "https://api.github.com"
        url = f"{host}/repos/{self.repository_name}" + (f"/{path}" if path else "")
        headers = {
            "Authorization": f"Bearer {self.token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "WearTAK-GitLab-release",
        }
        if data is not None:
            if binary:
                headers["Content-Type"] = "application/vnd.android.package-archive"
            else:
                headers["Content-Type"] = "application/json"
                data = json.dumps(data).encode()
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=120) as response:
                content = response.read()
                return json.loads(content) if content else None
        except urllib.error.HTTPError as error:
            if error.code == 404 and missing_ok:
                return None
            # Do not print request headers, tokens, signed URLs, or raw API responses.
            raise PublishError(
                f"GitHub {method} {path.split('?')[0]} returned HTTP {error.code}. "
                f"Check protected {TOKEN_VARIABLE} "
                "(repository Contents: write), "
                "personal repository access, rolling tag, and API availability."
            ) from None
        except urllib.error.URLError:
            raise PublishError(
                "Cannot reach the GitHub API. Check runner DNS, HTTPS egress, TLS trust, "
                "and GitHub availability; retry only after connectivity is restored."
            ) from None

    def download_digest(self, asset_id):
        request = urllib.request.Request(
            f"https://api.github.com/repos/{self.repository_name}/releases/assets/{asset_id}",
            headers={
                "Authorization": f"Bearer {self.token}",
                "Accept": "application/octet-stream",
                "X-GitHub-Api-Version": "2022-11-28",
                "User-Agent": "WearTAK-GitLab-release",
            },
        )
        try:
            with self.opener.open(request, timeout=120) as response:
                digest = hashlib.sha256()
                size = 0
                while chunk := response.read(1024 * 1024):
                    size += len(chunk)
                    digest.update(chunk)
                return size, digest.hexdigest()
        except urllib.error.HTTPError as error:
            raise PublishError(f"Asset verification download failed: HTTP {error.code}.") from None
        except urllib.error.URLError:
            raise PublishError(
                "Cannot download uploaded APK for verification. Check HTTPS access to "
                "GitHub asset storage endpoints; previous assets will not be deleted."
            ) from None

    def assets(self, release_id):
        result = []
        page = 1
        while True:
            batch = self.request("GET", f"releases/{release_id}/assets?per_page=100&page={page}")
            result.extend(batch)
            if len(batch) < 100:
                return result
            page += 1

    def sync_main(self, source_sha):
        if self.repository_name != PERSONAL_REPO:
            raise PublishError("Automatic source synchronization is personal-mirror only.")
        # Keep credentials out of arguments, URLs, files, helpers and captured output.
        env = {key: os.environ[key] for key in (
            "PATH", "HOME", "LANG", "LC_ALL", "TMPDIR", "TMP", "TEMP", "SystemRoot",
            "SSL_CERT_FILE", "SSL_CERT_DIR", "GIT_SSL_CAINFO", "GIT_SSL_CAPATH",
            "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "http_proxy", "https_proxy", "no_proxy",
        ) if key in os.environ}
        credentials = base64.b64encode(f"x-access-token:{self.token}".encode()).decode()
        settings = (
            ("credential.helper", ""),
            ("http.extraHeader", ""),
            (f"http.https://github.com/{PERSONAL_REPO}.git.extraHeader",
             f"Authorization: Basic {credentials}"),
            ("http.followRedirects", "false"),
            ("core.hooksPath", os.devnull),
        )
        env.update(GIT_CONFIG_COUNT=str(len(settings)), GIT_CONFIG_NOSYSTEM="1",
                   GIT_CONFIG_GLOBAL=os.devnull, GIT_TERMINAL_PROMPT="0")
        for index, (key, value) in enumerate(settings):
            env[f"GIT_CONFIG_KEY_{index}"] = key
            env[f"GIT_CONFIG_VALUE_{index}"] = value

        def git(step, *args, accepted=(0,)):
            result = subprocess.run(
                ["git", *args], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                text=True, check=False,
            )
            if result.returncode not in accepted:
                raise PublishError(
                    f"Personal main synchronization failed at {step} "
                    f"(Git exit code {result.returncode}). No force, tag changes or deletion "
                    "will be attempted; inspect canonical history and repository access."
                )
            return result

        if git("checkout inspection", "rev-parse", "HEAD").stdout.strip() != source_sha:
            raise PublishError("CI checkout does not match the publishing pipeline source.")
        path = "git/ref/heads/main"
        remote = self.request("GET", path)
        remote_sha = remote.get("object", {}).get("sha")
        if (remote.get("object", {}).get("type") != "commit"
                or not isinstance(remote_sha, str)
                or not re.fullmatch(r"[0-9a-f]{40}", remote_sha)):
            raise PublishError("Personal main must already reference a verified commit.")
        if remote_sha != source_sha:
            url = f"https://github.com/{PERSONAL_REPO}.git"
            git("main fetch", "fetch", "--no-tags", "--no-recurse-submodules", url, "refs/heads/main")
            if git("fetched ref inspection", "rev-parse", "FETCH_HEAD").stdout.strip() != remote_sha:
                raise PublishError("Personal main changed during source inspection; refusing update.")
            forward = git("forward ancestry", "merge-base", "--is-ancestor",
                          remote_sha, source_sha, accepted=(0, 1))
            if forward.returncode == 1:
                reverse = git("reverse ancestry", "merge-base", "--is-ancestor",
                              source_sha, remote_sha, accepted=(0, 1))
                if reverse.returncode == 1:
                    raise PublishError(
                        "Personal main has divergent history: forward ancestry and reverse "
                        "ancestry both returned Git exit code 1. Refusing publication."
                    )
                confirmed = self.request("GET", path)
                if confirmed.get("object", {}).get("sha") != remote_sha:
                    raise PublishError("Personal main changed during superseded readback; refusing update.")
                print("Skipping superseded pipeline: personal main already contains a newer source.")
                return "superseded"
            git("main push", "push", "--no-follow-tags", "--recurse-submodules=no",
                url, f"{source_sha}:refs/heads/main")
        confirmed = self.request("GET", path)
        if confirmed.get("object", {}).get("sha") != source_sha:
            raise PublishError("Personal main readback differs from the canonical pipeline source.")
        print(f"Verified {PERSONAL_REPO} main={source_sha} (non-force, no other refs changed).")
        return "synced"


def configuration(env):
    token_variable = TOKEN_VARIABLE
    expected = {
        "CI_SERVER_HOST": "git.tak.gov",
        "CI_PROJECT_PATH": "core/weartak-core/wearos-tak-civ",
        "CI_COMMIT_BRANCH": "main",
        "CI_COMMIT_REF_PROTECTED": "true",
        "CI_PIPELINE_SOURCE": "push",
    }
    for name, value in expected.items():
        if env.get(name) != value:
            raise PublishError(f"Publishing requires {name}={value}.")
    required = (token_variable, "CI_PROJECT_ID", "CI_PIPELINE_IID",
                "CI_PIPELINE_ID", "CI_PIPELINE_URL", "CI_COMMIT_SHA")
    for name in required:
        if not env.get(name):
            raise PublishError(
                f"Missing {name}. Configure {token_variable} as a masked, protected "
                "GitLab CI variable with access only to the destination repository."
            )
    for name in ("CI_PROJECT_ID", "CI_PIPELINE_IID", "CI_PIPELINE_ID"):
        if not re.fullmatch(r"[1-9][0-9]*", env[name]):
            raise PublishError(f"Invalid {name}.")
    if not re.fullmatch(r"[0-9a-f]{40}", env["CI_COMMIT_SHA"]):
        raise PublishError("Invalid source commit SHA.")
    expected_url = (
        f"https://git.tak.gov/core/weartak-core/wearos-tak-civ/-/pipelines/{env['CI_PIPELINE_ID']}"
    )
    if env["CI_PIPELINE_URL"] != expected_url:
        raise PublishError("Pipeline URL does not match the canonical source project.")
    return {
        "project_id": env["CI_PROJECT_ID"],
        "pipeline_iid": int(env["CI_PIPELINE_IID"]),
        "pipeline_id": env["CI_PIPELINE_ID"],
        "pipeline_url": env["CI_PIPELINE_URL"],
        "source_sha": env["CI_COMMIT_SHA"],
    }


def artifact(root):
    directory = root / OUTPUT
    apks = list(directory.glob("*.apk"))
    if len(apks) != 1 or apks[0].is_symlink() or not apks[0].is_file():
        raise PublishError("Expected exactly one signed release APK from main_release.")
    apk = apks[0]
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    checksum = (directory / "verified-apk.sha256").read_text().strip()
    if checksum != f"{digest}  {apk.relative_to(root).as_posix()}":
        raise PublishError("APK does not match the production-signature verification checksum.")
    metadata = json.loads((directory / "output-metadata.json").read_text())
    elements = metadata.get("elements", [])
    if (metadata.get("applicationId") != "com.aitech.weartak_civ"
            or metadata.get("variantName") != "release" or len(elements) != 1
            or elements[0].get("outputFile") != apk.name):
        raise PublishError("Gradle metadata must describe the single WearTAK release APK.")
    version = elements[0].get("versionName")
    code = elements[0].get("versionCode")
    if not isinstance(version, str) or not re.fullmatch(r"[0-9A-Za-z.+_-]+", version):
        raise PublishError("Invalid APK versionName.")
    if type(code) is not int or code <= 0:
        raise PublishError("Invalid APK versionCode.")
    return apk, digest, version, code


def state_from(release, project_id):
    matches = re.findall(rf"<!-- {MARKER} (.*?) -->", release.get("body") or "")
    if len(matches) != 1:
        raise PublishError("Rolling release is not automation-owned; refusing to modify it.")
    state = json.loads(matches[0])
    if (not isinstance(state, dict) or state.get("project_id") != project_id
            or type(state.get("pipeline_iid")) is not int
            or state["pipeline_iid"] <= 0):
        raise PublishError("Rolling release provenance is invalid or belongs to another project.")
    if release.get("tag_name") != TAG or release.get("prerelease"):
        raise PublishError("Unexpected rolling release tag/classification; refusing to modify it.")
    return state


def display_version(version, pipeline_iid):
    """User-facing build id, e.g. 5.8.0.3-p105 (drops +sha build metadata)."""
    short = version.split("+", 1)[0]
    suffix = f"-p{pipeline_iid}"
    return short if short.endswith(suffix) else short + suffix


def asset_pipeline(asset):
    """Pipeline IID of an asset this publisher owns, or None for anything else."""
    name = asset.get("name", "")
    match = ASSET_PATTERN.fullmatch(name)
    if match and asset.get("label") == name:
        return int(match[1])
    legacy = LEGACY_ASSET_PATTERN.fullmatch(name)
    if legacy and asset.get("label") == f"{LABEL_PREFIX}{legacy[2]}":
        return int(legacy[1])
    return None


def managed_asset(asset):
    return asset_pipeline(asset) is not None


def generated_body(state, version, code):
    return (
        "Automatically published stable WearOS main-channel build.\n\n"
        f"- APK version: **{version}** (versionCode **{code}**)\n"
        f"- Build: GitLab pipeline **{state['pipeline_id']}** "
        f"(project pipeline **{state['pipeline_iid']}**)\n"
        f"- Pipeline: {state['pipeline_url']}\n"
        f"- Source commit: `{state['source_sha']}` on TAK.gov `main`\n"
        f"- SHA256: `{state['sha256']}`\n\n"
        "Install the APK above. GitHub's \"Source code\" archives come from the fixed release "
        "anchor tag, not from this build; the source commit above is authoritative and may "
        "not yet be mirrored to GitHub.\n\n"
        f"<!-- {MARKER} {json.dumps(state, sort_keys=True)} -->"
    )


def update_body(previous_body, previous_state, generated):
    managed = f"{MANAGED_START}\n{generated}\n{MANAGED_END}"
    if previous_body is None:
        return managed
    marker = re.search(rf"<!-- {MARKER} .*? -->", previous_body)
    if MANAGED_START in previous_body or MANAGED_END in previous_body:
        if (previous_body.count(MANAGED_START) != 1 or previous_body.count(MANAGED_END) != 1
                or marker is None):
            raise PublishError("Ambiguous managed release boundaries; previous notes retained.")
        start = previous_body.index(MANAGED_START)
        end = previous_body.index(MANAGED_END)
        if not start + len(MANAGED_START) <= marker.start() < marker.end() <= end:
            raise PublishError("Invalid managed release boundaries; previous notes retained.")
        return previous_body[:start] + managed + previous_body[end + len(MANAGED_END):]

    # Migrate only an exact historical generated block. Unrecognized prose is never owned
    # merely because it is next to the provenance marker: retain it and replace only the marker.
    if marker is None:
        raise PublishError("Missing release provenance; previous notes retained.")
    for match in re.finditer(
        r"- APK version: \*\*([0-9A-Za-z.+_-]+)\*\* \(versionCode \*\*([1-9][0-9]*)\*\*\)",
        previous_body
    ):
        if "sha256" not in previous_state:
            continue
        legacy = generated_body(previous_state, match[1], int(match[2]))
        legacy = re.sub(rf"<!-- {MARKER} .*? -->", lambda _: marker[0], legacy)
        if previous_body.count(legacy) == 1:
            return previous_body.replace(legacy, managed, 1)
    return previous_body[:marker.start()] + managed + previous_body[marker.end():]


def publish(api, state, apk, digest, version, code):
    repository = api.request("GET", "")
    if (repository.get("full_name") != PERSONAL_REPO or
            repository.get("archived") is not False or
            repository.get("disabled") is not False or
            repository.get("private") is not False):
        raise PublishError(
            "Destination must be the explicitly approved, active public repository "
            f"{PERSONAL_REPO}; archived, disabled, different or nonpublic "
            "repositories are not allowed."
        )
    # Require an administrator-provisioned fixed tag. Never create/move a ref.
    if not api.request("GET", f"git/ref/tags/{TAG}", missing_ok=True):
        raise PublishError(
            f"Pre-create the fixed {TAG} tag on a commit already in the GitHub mirror. "
            "The publisher never pushes source commits or creates/moves tags."
        )
    release = api.request("GET", f"releases/tags/{TAG}", missing_ok=True)
    if release:
        previous = state_from(release, state["project_id"])
        if previous["pipeline_iid"] > state["pipeline_iid"]:
            print("Skipping older pipeline: a newer pipeline already owns the rolling release.")
            return
        if (previous["pipeline_iid"] == state["pipeline_iid"]
                and (previous.get("source_sha") != state["source_sha"]
                     or previous.get("sha256") != digest)):
            raise PublishError("Same pipeline has different source/artifact; refusing replacement.")

    state = dict(state, sha256=digest)
    short = display_version(version, state["pipeline_iid"])
    title = f"{TITLE} ({short})"
    body = update_body(
        release.get("body") if release else None,
        previous if release else None,
        generated_body(state, version, code),
    )
    if api.sync_main(state["source_sha"]) == "superseded":
        return "superseded"
    if not release:
        release = api.request("POST", "releases", {
            "tag_name": TAG, "name": title, "body": body,
            "draft": True, "prerelease": False, "make_latest": "false",
        })
    release_id = release["id"]
    assets = api.assets(release_id)
    name = f"WearTAK-{short}.apk"
    # GitHub shows the label instead of the file name, so keep it human-readable.
    label = name
    existing = [item for item in assets if item["name"] == name]
    if existing:
        uploaded = existing[0]
        if not managed_asset(uploaded):
            raise PublishError("Asset name collision with an unrelated asset; refusing replacement.")
    else:
        query = urllib.parse.urlencode({"name": name, "label": label})
        uploaded = api.request(
            "POST", f"releases/{release_id}/assets?{query}", apk.read_bytes(), binary=True
        )
    size = apk.stat().st_size
    if (uploaded.get("state") != "uploaded" or uploaded.get("size") != size
            or api.download_digest(uploaded["id"]) != (size, digest)):
        raise PublishError(
            "Uploaded APK failed size/SHA256 download verification. Previous assets retained; "
            "inspect only this pipeline's new asset before retrying."
        )
    api.request("PATCH", f"releases/{release_id}", {
        "name": title, "body": body, "draft": False,
        "prerelease": False, "make_latest": "false",
    })
    confirmed = api.request("GET", f"releases/{release_id}")
    confirmed_state = state_from(confirmed, state["project_id"])
    if (confirmed.get("draft") or confirmed.get("name") != title or confirmed.get("body") != body
            or confirmed_state != state
            or not any(a["id"] == uploaded["id"] and a.get("state") == "uploaded"
                       and a.get("size") == size for a in api.assets(release_id))):
        raise PublishError("Release update could not be verified; previous assets retained.")
    # Delete only older assets explicitly owned by this publisher, after verification.
    for old in assets:
        owner = asset_pipeline(old)
        if owner is not None and owner < state["pipeline_iid"]:
            api.request("DELETE", f"releases/assets/{old['id']}")
    print(f"Published {title}: version {version} ({code}), SHA256 {digest}")


def main(argv=()):
    try:
        if list(argv):
            raise PublishError("Publisher always targets the fixed personal repository.")
        state = configuration(os.environ)
        apk, digest, version, code = artifact(Path.cwd())
        publish(GitHub(os.environ[TOKEN_VARIABLE]), state, apk, digest, version, code)
    except (PublishError, OSError, ValueError, KeyError, TypeError, urllib.error.URLError) as error:
        # urllib transport errors can contain signed URLs; do not echo their details.
        message = str(error) if isinstance(error, PublishError) else type(error).__name__
        print(
            f"ERROR: {message} Publication incomplete; existing usable assets are retained "
            "until verified replacement. See ci/README.md for setup/recovery.",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
