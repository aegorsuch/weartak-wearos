# Rolling GitHub pipeline release

Successful **protected `main` push pipelines** from
`git.tak.gov/core/weartak-core/wearos-tak-civ` publish the final production-signed
`main_release` APK to the fixed GitHub mirror
`Ascent-Integrated-Tech/wearos-tak-civ`. The visible title is
**TAK.gov Forge CI/CD Pipeline Release (<version>)**, e.g.
`TAK.gov Forge CI/CD Pipeline Release (5.8.0.3-p105)`, where `<version>` is the APK
versionName without its `+<sha>` build metadata. The APK is attached as
`WearTAK-<version>.apk`. The release tag is
`TAK.gov-Forge-CI-CD-Pipeline-Release` (git tags cannot contain spaces or `/`).

By project policy, `main` builds are stable: this release uses
`prerelease=false`, not a prerelease. This classification is an explicit project
decision, not an implication of using Android's release signing/build variant.
The actual APK versionName/versionCode, canonical source SHA, pipeline ID/IID,
pipeline URL, and APK SHA256 appear in the release body. The rolling release
does not take over GitHub's "Latest" designation from other numbered releases.
Official `v...` pipelines and all existing numbered/stable releases remain unchanged.

The additional approved public personal mirror is
[`aegorsuch/weartak-wearos`](https://github.com/aegorsuch/weartak-wearos).
It uses the same publisher and signed pipeline artifact, not an independent build.
The existing company destination remains the default; personal publication is the
fixed `--personal` mode described below, not an arbitrary destination override.

## Administrator setup (separate approval required)

1. Public signed-APK distribution to the exact fixed destination
   `Ascent-Integrated-Tech/wearos-tak-civ` was explicitly approved on 2026-10-19.
   The mirror was private at the original setup and is now public. The publisher
   permits either visibility for this approved destination only and checks its
   exact identity and active status before every update. Other, archived, disabled
   or unknown-status destinations fail closed. This job never changes repository
   visibility or access; any different destination needs separate approval.
2. Protect canonical GitLab `main` and restrict who may push/merge, edit pipeline
   configuration, use protected runners, or override pipeline variables.
3. In the GitHub mirror, create the fixed tag `TAK.gov-Forge-CI-CD-Pipeline-Release` on an
   existing mirrored commit, with separate approval. (It replaced the original
   `wearos-development` anchor, whose release is no longer updated.)
   Do not move the tag on each build. The publisher checks that it exists before
   creating a release, never pushes commits, and has no tag-management calls.
   The mirror was behind canonical `gov/develop` at inspection, so a GitLab
   source SHA must **not** be used as an unverified GitHub `target_commitish`.
   The fixed tag is only an anchor; the APK's authoritative source SHA is in the
   release metadata. GitHub's automatic source archives refer to the fixed anchor,
   not necessarily the APK source. The job uploads **only the signed APK**, never
   custom source bundles, logs, checksums as assets, signing files, or credentials.
4. Create a fine-grained GitHub token scoped **only** to this mirror with
   **Contents: read and write** (metadata read is implicit), an appropriate expiry,
   and organization approval/SSO authorization if required. No administration,
   workflows, issues, or other repository permissions are needed. Restrict the
   token owner to this automation as appropriate. Do not copy local `gh`/`glab`
   credentials or reuse a broad personal token.
5. Add `WEARTAK_GITHUB_TOKEN` to the canonical GitLab project's CI/CD variables as
   **masked**, **protected**, and hidden if supported; disable variable expansion.
   Make it available to the publish job's environment scope. Never place it in YAML,
   repository files, logs, or artifacts. Rotate it according to organization policy.
6. Allow the existing runner to pull `python:3.12-slim` (stdlib only, no package
   installation), and allow HTTPS to `api.github.com`, `uploads.github.com`, and
   GitHub's signed release-asset storage endpoints. The Android build continues
   using the existing TAK Gradle launcher and production-certificate verification.
7. Review/approve and merge these changes through the normal ticket process.
   A subsequent authorized protected `main` push will run publication. Do not
   trigger a pipeline or publish manually merely to complete setup.

Missing variables, missing anchor tag, invalid artifacts, denied API access, and
verification failures fail the publish job with actionable diagnostics. The build's
signed APK remains available as a GitLab artifact for 30 days even when publication
fails. An unprotected pipeline is intentionally excluded from publication by rules,
not reported as a successful upload. The script also enforces the source gates.

## Additional personal mirror

`publish_personal_github_main` inherits the company's canonical-only protected
`main` push rules, successful earlier-stage gate, same-pipeline
`main_release` artifact dependency, noninterruptible behavior and
`wearos-github-development` serialized resource group. It runs the shared script
with `--personal`. No second Android build, signer, GitHub Actions build, manual
dispatch or signing credential in GitHub is introduced. Company publication and
its release-testing notification are unchanged; the personal mirror sends no
duplicate tester notification and its GitHub Actions remain disabled.

The only personal destination is the active **public**
`aegorsuch/weartak-wearos`. The fixed rolling anchor was provisioned without moving
the company's tag: `2a7f47f707d3b9419f27f0036caa3cb8024f1f3b`, verified as
canonical public `main` ancestry. Neither publisher creates or moves tags.
A tag alone is not a GitHub release or an APK upload.

Before personal artifact publication, the job verifies its checkout is the exact
pipeline source SHA, inspects and fetches the existing personal `main`, requires
it to be an ancestor of that source, pushes **only** that commit to `main`
without force, tag following, submodule pushes or deletions, then verifies the
remote SHA. Missing/divergent/ahead state or a tip change detected during fetch
fails closed; the normal non-force push also rejects non-fast-forward races.
This is not a lock against external writers and never adopts unrelated work or
rewrites history. A newer owned
release skips an older pipeline before source synchronization. Complete canonical
history (`GIT_DEPTH=0`) and `python:3.12-bookworm` (includes Git) support this check.
Git authentication is restricted to the fixed repository through the child
process environment, never command arguments, credential files/helpers or logs;
redirects, inherited credential helpers and repository hooks are disabled.

Ongoing automatic source synchronization covers **main only**, at each natural
protected canonical main publication. The initial public `master` and 70
canonical/company-matching historical tags are preserved, not automatically
rewritten or expanded to private/agent branches. The fixed release tag stays
unchanged; automatic GitHub source archives still refer to its old anchor.
An upload failure may leave source advanced while the previous usable APK remains;
the source SHA in each APK's verified release metadata is authoritative.

Use a **separate** fine-grained token owned by `aegorsuch`, scoped only to
`weartak-wearos`, with **Contents: read and write** (implicit Metadata read).
Do not reuse the company token, another personal repository's token, or a local
`gh` credential. In the canonical project's
[Settings > CI/CD > Variables](https://git.tak.gov/core/weartak-core/wearos-tak-civ/-/settings/ci_cd),
add `WEARTAK_PERSONAL_GITHUB_TOKEN`: protected, masked, hidden when supported,
environment scope `*`, expansion disabled. Canonical `main` must be protected.
No agent needs to inspect, copy or print its value. GitHub may require additional
workflow permission for a future source commit that changes `.github/workflows`;
the Contents-only token must fail rather than silently expanding access.

The personal release uses the same version/tag/title, stable/non-Latest status,
production-verified APK, checksum, canonical source/pipeline provenance,
download verification, ownership, manual-note preservation, retry and retention
rules below. Repository/variable setup alone does **not** prove activation:
acceptance requires a subsequent normally merged, naturally signed pipeline to
succeed and both destinations' APK bytes/checksum and source metadata to match.

## Ordering and safe retention

The final `publish` stage waits for **all** earlier jobs to succeed; it downloads
artifacts only from this pipeline's `main_release`. That job verifies the
production signing certificate, then writes `verified-apk.sha256`. The publisher
requires exactly one APK matching this checksum and Gradle release metadata.
The checksum and metadata are GitLab-only provenance, not additional GitHub assets.

GitLab's `wearos-github-development` resource group serializes all updates from
this project regardless of pipeline ordering. Keep this as the **only writer**
to the rolling release; do not run the script elsewhere or edit its ownership
marker manually. The release's project ID and pipeline IID prevent an older
pipeline (including retries) from replacing a successfully published newer one.
Reusing a pipeline IID with different bytes/source fails rather than overwriting.
This is not a cross-project/distributed lock against other writers.

Generated release prose is bounded by hidden
`weartak-release-managed:start` / `weartak-release-managed:end` comments.
Manual notes before or after that block are preserved byte-for-byte; keep notes
outside it and do not edit its provenance marker or boundaries. Existing unbounded
releases migrate only an exact recognized historical generated block. Otherwise
only the ownership marker is replaced by a new managed block and all unknown
prose is retained, even if it resembles old generated text. Missing, duplicate or
misordered boundaries fail before upload or cleanup. The complete updated body,
including retained notes, must match the API readback before old assets are removed.
This prevents automated overwrites of existing notes, not concurrent editing by
another writer during a publish; retain the single-writer restriction above.

The first release is created as a draft. Each APK receives a unique `WearTAK-<version>.apk`
name (the pipeline IID is part of `<version>`), is uploaded alongside the previous APK, then is downloaded using the
authenticated asset API and checked for exact size and SHA256. Only after that
does the job publish/update the release metadata and confirm it by reading it back.
It then deletes **only older assets on this one release** with both the exact
`WearTAK-<version>-p<IID>.apk` naming scheme and an identical label (GitHub
shows the label in place of the file name), or the legacy
`weartak-development-p<IID>-<SHA256>.apk` name with its
`weartak-rolling-v1:<SHA256>` label.

Other releases, tags, unrelated assets, and future-pipeline assets are untouched.
There is no historical-release sweep. A failure before replacement verification
leaves previous usable assets in place; a failed first upload may leave a draft.
Failure during cleanup is reported as a failure even if the new APK is usable.
Retrying the same job reuses and re-verifies a matching existing upload and resumes
cleanup; it does not blindly overwrite assets. GitHub updates are not transactional:
a lost response may leave a new asset or updated metadata, but never justifies
deleting old usable assets without verification.

If an upload is incomplete/corrupt, the retry fails safely rather than deleting it
blindly. An authorized administrator must inspect/remove **only that pipeline's
failed asset**, then retry. Do not remove the previous usable APK, other assets,
the tag, or release ownership metadata. A human-created release on the reserved tag
without the expected ownership marker is refused, not silently adopted.

## Local validation

Run from the repository root with Python 3.12:

```text
python -m unittest discover -s ci/tests -v
```

Tests use in-memory mocked GitHub responses/fixtures, never a token or real uploads.
They cover source/configuration gates, artifact integrity, initial stable publication,
ordering, repeat retries, ownership and retention boundaries, API/verification failures,
pagination, and stripping authorization on storage redirects. These checks do not
prove runner registry/network access, protected-variable delivery, token authorization,
or real GitHub upload behavior. Those remain end-to-end acceptance checks after
separate approval and administrator setup. No Android build is needed for CI-only tests.

## Release-testing checklist for testers

After a release is published **and verified**, the publisher sends a GitHub
`repository_dispatch` (`weartak-release-published`) with the pipeline ID. The
token's existing Contents: write permission covers this; no new scopes are needed.
If the notification fails, the job fails with a clear message and can be retried;
nothing is duplicated.

`.github/workflows/release-testing.yml` handles it on the GitHub mirror. GitHub only
runs workflows from the default branch, so `.github/workflows/release-testing.yml`
and `ci/release-testing/` must also be present on the mirror's `main` (an approved
exception to publishing source to GitHub). Keep both copies identical.

The workflow re-reads the release (it never trusts the payload), skips superseded
pipelines, and opens one issue per pipeline titled
`Release testing: <release title>`. The issue has the APK download link, the release
details and `ci/release-testing/checklist.md`, and is assigned to the user in the
repository variable `RELEASE_TESTER` (currently `paul-weartak`, with Write access so
they can tick boxes). Existing issues, including closed ones, are never recreated.
If the tester has not accepted the repository invitation yet, the issue @mentions
them unassigned; running the workflow again (**Actions > Release testing checklist >
Run workflow**) assigns it.

Edit `ci/release-testing/checklist.md` when features change. Tests:
`python -m unittest discover -s ci/tests` and
`node --test ci/release-testing/create_release_test_issue.test.cjs`.
