import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from test_publish_github_release import FakeGitHub, environment
from test_diagnose_agp_resolution import find_shell


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("draft_publisher", ROOT / "ci/publish_github_release.py")
p = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(p)
SH = find_shell()


class ChangelogTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git("init", "-q")
        self.git("config", "user.name", "Fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        self.git("config", "core.autocrlf", "false")
        (self.root / "app").mkdir()
        (self.root / "app/build.gradle").write_text(
            'def baseVersionCode = 584\n def baseVersionName = "5.8.4"\n'
        )
        (self.root / "README.md").write_text("unchanged approved notes\n")
        self.base = self.commit()
        (self.root / "app/code.kt").write_text("code\n")
        self.source = self.commit()
        self.env = environment() | {"CI_COMMIT_SHA": self.source}
        self.state = p.configuration(self.env)
        self.previous = self.state | {"pipeline_iid": 11, "source_sha": self.base}
        self.version = f"5.8.4-p12+{self.source[:8]}"
        self.code = 5840012
        output = self.root / p.OUTPUT
        output.mkdir(parents=True)
        self.apk = output / "WearTAK-release.apk"
        self.apk.write_bytes(b"signed fixture")
        self.digest = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        (output / "verified-apk.sha256").write_text(
            f"{self.digest}  {self.apk.relative_to(self.root).as_posix()}\n"
        )
        (output / "output-metadata.json").write_text(json.dumps({
            "applicationId": "com.aitech.weartak_civ", "variantName": "release",
            "elements": [{"versionCode": self.code, "versionName": self.version,
                          "outputFile": self.apk.name}],
        }))

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.root, text=True).strip()

    def commit(self):
        self.git("add", ".")
        self.git("commit", "-qm", "fixture")
        return self.git("rev-parse", "HEAD")

    def publish(self, api):
        p.publish(api, self.state, *p.artifact(self.root), receipt_root=self.root)

    def generate(self):
        env = os.environ | self.env
        env.pop(p.TOKEN_VARIABLE, None)
        with patch.dict(p.os.environ, env, clear=True), \
                patch.object(p.Path, "cwd", return_value=self.root):
            return p.main(["--changelog-draft"])

    def test_exact_range_version_review_candidate_and_readme_unchanged(self):
        before = (self.root / "README.md").read_bytes()
        api = FakeGitHub(self.previous)
        self.publish(api)
        self.assertFalse((self.root / "changelog-drafts").exists())
        self.assertEqual(self.generate(), 0)
        text = (self.root / "changelog-drafts/p12.md").read_text(encoding="utf-8")
        for value in (self.version, str(self.code), self.base, self.source,
                      "REVIEW REQUIRED", "Updates WearOS application.",
                      "1 source commits", self.state["pipeline_url"]):
            self.assertIn(value, text)
        self.assertEqual((self.root / "README.md").read_bytes(), before)
        self.assertNotIn("mock-personal-token", text)
        self.assertNotIn("signed fixture", text)

    def test_retry_uses_original_base_and_one_draft_per_build(self):
        api = FakeGitHub(self.previous)
        self.publish(api)
        self.assertEqual(self.generate(), 0)
        first = (self.root / "changelog-drafts/p12.md").read_bytes()
        self.publish(api)
        self.assertEqual(self.generate(), 0)
        self.assertEqual((self.root / "changelog-drafts/p12.md").read_bytes(), first)
        self.assertEqual(len(list((self.root / "changelog-drafts").iterdir())), 1)

    def test_failed_or_unpublished_release_never_gets_draft(self):
        self.assertEqual(self.generate(), 1)
        api = FakeGitHub(self.previous)
        api.bad_download = True
        with self.assertRaises(p.PublishError):
            self.publish(api)
        self.assertFalse((self.root / "changelog-receipt.json").exists())
        self.assertFalse((self.root / "changelog-drafts").exists())

    def test_missing_provenance_fails_draft_not_publication(self):
        api = FakeGitHub()
        self.publish(api)
        self.assertFalse(api.release["draft"])
        self.assertEqual(self.generate(), 1)
        self.assertFalse((self.root / "changelog-drafts").exists())

    def test_superseded_pipeline_has_no_receipt_or_draft(self):
        for api in (FakeGitHub(self.previous | {"pipeline_iid": 13}),
                    FakeGitHub(self.previous)):
            api.sync_result = "superseded"
            self.publish(api)
            self.assertFalse((self.root / "changelog-receipt.json").exists())
            self.assertFalse((self.root / "changelog-drafts").exists())

    def test_mismatched_receipt_is_rejected(self):
        self.publish(FakeGitHub(self.previous))
        receipt = self.root / "changelog-receipt.json"
        value = json.loads(receipt.read_text())
        value["source_sha"] = self.base
        receipt.write_text(json.dumps(value))
        self.assertEqual(self.generate(), 1)
        self.assertFalse((self.root / "changelog-drafts").exists())

    def test_docs_only_mirrors_exact_source_without_apk_release_or_draft(self):
        api = FakeGitHub(self.previous)
        with patch.dict(p.os.environ, self.env), patch.object(p, "GitHub", return_value=api), \
                patch.object(p, "artifact") as artifact, patch.object(p, "publish") as publish:
            self.assertEqual(p.main(["--mirror-only"]), 0)
        self.assertEqual(api.synced_sources, [self.source])
        artifact.assert_not_called()
        publish.assert_not_called()
        self.assertEqual(api.calls, [("GET", "", None), ("SYNC", self.source, None)])
        self.assertFalse((self.root / "changelog-drafts").exists())

    def test_source_only_rejects_wrong_destination_and_reports_sync_failure(self):
        for wrong_destination in (True, False):
            api = FakeGitHub(self.previous)
            if wrong_destination:
                api.repository["private"] = True
            with patch.dict(p.os.environ, self.env), patch.object(p, "GitHub", return_value=api), \
                    patch.object(api, "sync_main", side_effect=p.PublishError("sync failed")):
                self.assertEqual(p.main(["--mirror-only"]), 1)
            self.assertFalse(any(call[0] in ("POST", "PATCH", "DELETE") for call in api.calls))

    def test_wrong_version_code_sha_range_or_checksum_rejected(self):
        for version, code, previous in (
            ("5.8.4-p99+" + self.source[:8], self.code, self.previous),
            (self.version, 1, self.previous),
            (self.version, self.code, self.previous | {"source_sha": "f" * 40}),
        ):
            with self.subTest(version=version, code=code, previous=previous):
                with self.assertRaises(p.PublishError):
                    p.changelog_draft(self.root, self.state, previous, version, code)
        self.publish(FakeGitHub(self.previous))
        self.apk.write_bytes(b"tampered")
        self.assertEqual(self.generate(), 1)
        self.assertFalse((self.root / "changelog-drafts").exists())

    def test_unprotected_noncanonical_or_nonpush_pipeline_rejected(self):
        for key, value in (("CI_COMMIT_REF_PROTECTED", "false"), ("CI_COMMIT_BRANCH", "dev"),
                           ("CI_SERVER_HOST", "other.invalid"),
                           ("CI_PIPELINE_SOURCE", "merge_request_event")):
            with self.subTest(key=key):
                with self.assertRaises(p.PublishError):
                    p.configuration(self.env | {key: value})

    @unittest.skipUnless(SH, "POSIX shell required for CI eligibility fixture")
    def test_only_readme_changes_skip_but_other_docs_config_and_code_release(self):
        def eligibility(before, source):
            return subprocess.run(
                [SH, str(ROOT / "ci/release_eligibility.sh")], cwd=self.root,
                env=os.environ | {"CI_COMMIT_BEFORE_SHA": before, "CI_COMMIT_SHA": source},
                text=True, capture_output=True,
            )

        self.assertEqual(eligibility(self.base, self.source).stdout.strip(), "release")
        # Ignore local signed-artifact fixtures when committing only the changed file.
        for filename, expected in (("README.md", "docs-only"), ("ci/README.md", "docs-only"),
                                   ("LICENSE.md", "release"), (".gitlab-ci.yml", "release"),
                                   ("app/code.kt", "release")):
            before = self.git("rev-parse", "HEAD")
            path = self.root / filename
            path.parent.mkdir(exist_ok=True)
            path.write_text("changed\n")
            self.git("add", filename)
            self.git("commit", "-qm", "fixture update")
            source = self.git("rev-parse", "HEAD")
            result = eligibility(before, source)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.strip(), expected, filename)
        self.assertNotEqual(eligibility("f" * 40, source).returncode, 0)
        self.assertEqual(eligibility("0" * 40, source).stdout.strip(), "release")

    def test_ci_draft_waits_for_publication_and_keeps_restricted_retention(self):
        ci = (ROOT / ".gitlab-ci.yml").read_text()
        job = ci.split("\nchangelog_draft:", 1)[1]
        self.assertIn("stage: draft", job)
        self.assertIn("- main_release", job)
        self.assertIn("- publish_personal_github_main", job)
        self.assertIn("when: on_success", job)
        self.assertIn("expire_in: 30 days", job)
        self.assertIn("access: developer", job)
        self.assertNotIn("allow_failure", job)
        self.assertNotIn("needs:", job)
        self.assertEqual(ci.count("sh ci/release_eligibility.sh"), 3)
        publisher = ci.split("\npublish_personal_github_main:", 1)[1].split(
            "\nchangelog_draft:", 1
        )[0]
        self.assertIn("python ci/publish_github_release.py --mirror-only || exit $?", publisher)
        self.assertIn("resource_group: wearos-github-development", publisher)


if __name__ == "__main__":
    unittest.main()
