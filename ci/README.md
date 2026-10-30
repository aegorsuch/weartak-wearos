# Rolling GitHub pipeline release

## Host full-lint quality gate

`host_lint_debug` runs full `:app:lintDebug` in normal branch/tag pipelines, including MR source-branch
pushes, matching the existing publisher-test job's pipeline scope.
Lint errors fail the job; warnings remain visible but are not promoted to errors.
No baseline or check-ID filter is used. Lint reports are
retained for 14 days, including on failure. The job uses the existing Android
launcher image and bounded workers/in-process Kotlin compilation, without release
signing, device installation or publication. Existing release/publication jobs
still wait for the test stage on their authorized main/tag pipelines. Run
`:app:testDebugUnitTest :app:assembleDebug` separately when validating code changes;
this new gate does not change the project's existing unit-test policy.

## Manual AGP resolution diagnostic

`agp_resolution_diagnostic` is an optional, nonblocking manual job using the
same Gradle launcher image tag as release builds. It has no artifact dependencies,
does not recreate signing files, and runs only bounded Google Maven POM requests
and Gradle `help --info --stacktrace`, not assembly, signing, or publication.
It does not print routing tables, DNS configuration, proxy environment variables,
HTTP headers, or response bodies. Review diagnostic logs before sharing them.

Run this job only in an already-authorized pipeline; do not create or retry a
release pipeline for diagnostics, since normal pipeline publication rules still
apply. The job reports its source SHA. Compare runner/image/network context with
the failing job; a matching image tag alone does not establish a matching digest.
No automatic retry or dependency downgrade is configured. Repository routing now
uses the credential-gated TAK Artifactory configuration described below.

AGP `8.13.2` and all build configuration files were identical between successful
`c48ba2544d25c7419c2832d4089efa20a35e2700` (job `7666745`) and failed
`dd55f2197d6353fdc1091ba95440139faf310542` (job `7670484`). Both public POMs
were reachable from the investigation workstation, which does not establish
runner reachability. Curl reports HTTP status and connection errors; Gradle
output distinguishes Java-side resolution failures. Successful POM requests
alone do not prove all transitive artifacts are reachable.

## AGP resolution fix

The diagnostic added in GitLab MR !558 showed Google POM probes returning HTTP
200 while Gradle reported Maven Central HTTP 429. An unmerged Google-exclusive
routing experiment resolved the Android plugin marker but then encountered a
KSP marker HTTP 429; that experiment is not the production fix. Shared NAT or
Gradle's general fallback behavior was not established by this evidence.

Platform MR !559, merged as `517190128091a1bd89629d9d88602a93faac4881`,
changed only `settings.gradle`. It puts the approved repository
`https://artifacts.tak.gov/artifactory/maven-release` first for both plugin and
dependency resolution when both a username and password are available:

- Username: `ARTIFACTORY_CONSUME_USER`, then `ARTIFACTORY_USER`, then `TAK_USERNAME`.
- Password: `ARTIFACTORY_CONSUME_PASSWORD`, then `ARTIFACTORY_PASSWORD`, then `TAK_PASSWORD`.

Username and password fallbacks are evaluated independently. Never log their
values. Blank aliases are skipped. Gradle CI jobs now extend
`.artifactory_credentials`, using the approved
`devsecops/gitlab-ci-common` `/template/artifactory_token_service.yml`
token exchange. The template's global `before_script` is explicitly cleared;
only Gradle jobs request a token. Release signing setup preserves the credential
bootstrap through `!reference` before its existing keystore checks.
Launcher-image jobs use `.artifactory_service_create_token_no_depends_bash`,
matching canonical LACE `1772e1bd`: the launcher already contains the required
tools, so they must not invoke the generic template's apt package installation.
Robolectric's separate Android runtime resolver receives the same approved
repository and credentials when both are available; no static credentials
or token values are committed or printed.

`android_mopp_check` runs debug assembly and focused host/plugin regression
tests on merge requests and main pushes without production signing setup.
Main release jobs still wait for successful test-stage jobs. Existing release
lint checks remain enabled. Full `lintDebug` is not a new blocking gate: the
current host has an existing backlog (221 errors at this migration), including
untouched permission checks and missing translations; do not suppress those
findings or generate a mass baseline to ship an unrelated change.

Settings print `takArtifactory for plugins: enabled` or
`takArtifactory for plugins: disabled (no credentials in environment)`.
Without both credentials, the Artifactory repository is omitted.
Remaining plugin repositories are filtered Google (`com.android.*`,
`com.google.*`, `androidx.*`), Plugin Portal, then Maven Central; dependencies
retain Google, Maven Central and the existing local `libs` repository.

AGP `8.13.2`, Gradle `8.14.5`, CI JDK `17.0.2`, launcher image tag
`17-2.6.2` and production signing configuration were not changed by this fix.
[Main pipeline 1817472](https://git.tak.gov/core/weartak-core/wearos-tak-civ/-/pipelines/1817472)
on the merge commit succeeded:
[release job 7673187](https://git.tak.gov/core/weartak-core/wearos-tak-civ/-/jobs/7673187)
verified the production signature for `WearTAK-5.8.0.3-p454.apk`, and
[publisher job 7673188](https://git.tak.gov/core/weartak-core/wearos-tak-civ/-/jobs/7673188)
updated the existing personal GitHub rolling release. Matching image tags alone
do not prove matching image digests or runner network/cache conditions.

## Release container memory

Release job `7675487` on naming-fix merge `e7585a2a` failed with Kubernetes
`OOMKilled` / exit `137` after compilation and release optimization tasks.
The runner reported an `8Gi` container limit; the repository specified a
`4096m` Gradle heap, as in successful p454. Those limits are not equivalent:
the container also holds JVM native memory, Kotlin compilation and build workers.
The trace provides no peak-memory measurement or proof of which process caused
the kill. This was not an AGP resolution failure or a Java heap exception.

The shared release command now caps Gradle workers at two and uses
`-Pkotlin.compiler.execution.strategy=in-process`, so Kotlin shares the existing
Gradle heap instead of starting a separate compiler daemon. The `4Gi` heap and
`8Gi` container limit remain unchanged, as do release minification, resource
shrinking, signing and publication gates. These are CI-only execution controls;
local build defaults are unchanged. A successful production pipeline is still
required to validate this memory mitigation; configuration checks cannot prove
peak RSS fits the runner limit. If another kill occurs, obtain container
peak-memory/OOM metrics before choosing further limits or requesting more memory.

## Rolling release behavior

WearOS base version is `5.8.4`, base code `584`. CI retains the existing
`584 * 10000 + CI_PIPELINE_IID` code and `5.8.4-p<IID>+<sha>` name convention;
this increases codes above the previous `583` base without changing signing or
the fixed rolling-release URL.

Successful **protected `main` push pipelines** from
`git.tak.gov/core/weartak-core/wearos-tak-civ` publish the final production-signed
`main_release` APK only to the fixed public personal GitHub repository
[`aegorsuch/weartak-wearos`](https://github.com/aegorsuch/weartak-wearos). The visible title is
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
The pipeline does not publish to, update, or delete historical company Releases or tags.

## Administrator setup

1. Protect canonical GitLab `main` and restrict who may push/merge, edit pipeline
   configuration, use protected runners, or override pipeline variables.
2. Verify the fixed tag `TAK.gov-Forge-CI-CD-Pipeline-Release` in
   `aegorsuch/weartak-wearos` remains on an existing mirrored commit. (It replaced
   the original `wearos-development` anchor, whose release is no longer updated.)
   Do not move the tag on each build. The publisher checks that it exists before
   creating a release, never pushes commits, and has no tag-management calls.
   The mirror was behind canonical `gov/develop` at inspection, so a GitLab
   source SHA must **not** be used as an unverified GitHub `target_commitish`.
   The fixed tag is only an anchor; the APK's authoritative source SHA is in the
   release metadata. GitHub's automatic source archives refer to the fixed anchor,
   not necessarily the APK source. The job uploads **only the signed APK**, never
   custom source bundles, logs, checksums as assets, signing files, or credentials.
3. Create a fine-grained GitHub token scoped **only** to `weartak-wearos` with
   **Contents: read and write** (metadata read is implicit), an appropriate expiry,
   and organization approval/SSO authorization if required. No administration,
   workflows, issues, or other repository permissions are needed. Restrict the
   token owner to this automation as appropriate. Do not copy local `gh`/`glab`
   credentials or reuse a broad personal token.
4. Add `WEARTAK_PERSONAL_GITHUB_TOKEN` to the canonical GitLab project's CI/CD variables as
   **masked**, **protected**, and hidden if supported; disable variable expansion.
   Make it available to the publish job's environment scope. Never place it in YAML,
   repository files, logs, or artifacts. Rotate it according to organization policy.
5. Allow the existing runner to pull `python:3.12-bookworm` (Git is needed for
   source synchronization; Python uses only its standard library), and allow HTTPS
   to `api.github.com`, `uploads.github.com`, and
   GitHub's signed release-asset storage endpoints. The Android build continues
   using the existing TAK Gradle launcher and production-certificate verification.
6. Review/approve and merge these changes through the normal ticket process.
   A subsequent authorized protected `main` push will run publication. Do not
   trigger a pipeline or publish manually merely to complete setup.

Missing variables, missing anchor tag, invalid artifacts, denied API access, and
verification failures fail the publish job with actionable diagnostics. The build's
signed APK remains available as a GitLab artifact for 30 days even when publication
fails. An unprotected pipeline is intentionally excluded from publication by rules,
not reported as a successful upload. The script also enforces the source gates.

## Personal destination and source synchronization

`publish_personal_github_main` is the only publisher job. It uses the protected
canonical `main` push gate, waits for all earlier stages, downloads the same-pipeline
`main_release` artifact, and is noninterruptible. It keeps the existing
`wearos-github-development` resource group so in-flight publishers stay serialized
during cutover. No second Android build, signer, company-repository write, or
company token is used. Historical company Releases and tags remain untouched.

The only destination is the active **public** `aegorsuch/weartak-wearos`. The
fixed rolling anchor was provisioned at
`2a7f47f707d3b9419f27f0036caa3cb8024f1f3b`, verified as canonical public `main`
ancestry. The publisher never creates or moves tags. A tag alone is not a GitHub
release or an APK upload.

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

Use a fine-grained token owned by `aegorsuch`, scoped only to
`weartak-wearos`, with **Contents: read and write** (implicit Metadata read).
Do not use a company token, another personal repository's token, or a local
`gh` credential. In the canonical project's
[Settings > CI/CD > Variables](https://git.tak.gov/core/weartak-core/wearos-tak-civ/-/settings/ci_cd),
add `WEARTAK_PERSONAL_GITHUB_TOKEN`: protected, masked, hidden when supported,
environment scope `*`, expansion disabled. Canonical `main` must be protected.
No agent needs to inspect, copy or print its value. GitHub may require additional
workflow permission for a future source commit that changes `.github/workflows`;
the Contents-only token must fail rather than silently expanding access.

The release uses the same version/tag/title, stable/non-Latest status,
production-verified APK, checksum, canonical source/pipeline provenance,
download verification, ownership, manual-note preservation, retry and retention
rules below. Repository/variable setup alone does **not** prove activation:
acceptance requires a subsequent normally merged, naturally signed pipeline to
succeed and the personal release APK checksum/source metadata to match the
verified GitLab artifact and pipeline.

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

## Manual release-testing checklist

The GitLab publisher does not start the release-testing workflow or send a
`repository_dispatch`. After a release is published and verified, run
**Actions > Release testing checklist > Run workflow** on the GitHub mirror.
GitHub only runs workflows from the default branch, so
`.github/workflows/release-testing.yml` and `ci/release-testing/` must also be
present on the mirror's `main` (an approved exception to publishing source to
GitHub). Keep both copies identical.

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
