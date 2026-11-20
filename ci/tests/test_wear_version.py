import contextlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
import urllib.parse

from ci import wear_version as v


ROOT = Path(__file__).resolve().parents[2]


class VersionTests(unittest.TestCase):
    def test_numeric_version_order_requires_code_increase_and_rejects_downgrades(self):
        for previous, current in [
            (("5.8.4", 584), ("5.8.5", 584)),
            (("5.8.4", 584), ("5.8.4.1", 584)),
            (("5.8.9", 589), ("5.8.10", 589)),
            (("5.8.4", 584), ("5.8.3", 585)),
            (("5.8.4", 584), ("5.8.4", 583)),
        ]:
            with self.subTest(previous=previous, current=current):
                with self.assertRaises(v.VersionError):
                    v.validate_upgrade(previous, current)
        v.validate_upgrade(("5.8.9", 589), ("5.8.10", 590))
        v.validate_upgrade(("5.8.4", 584), ("5.8.4", 584))
        v.validate_upgrade(("5.8.4", 584), ("5.8.4.1", 585))

    def test_manifest_bounds_cover_every_supported_pipeline_iid(self):
        name, code = v.parse_manifest("versionName=5.8.4\nbaseVersionCode=209999\n")
        self.assertEqual(name, "5.8.4")
        codes = [code * 10000 + iid for iid in range(1, 10000)]
        self.assertEqual(2099990001, min(codes))
        self.assertEqual(2099999999, max(codes))
        self.assertTrue(all(0 < value <= v.MAX_ANDROID_CODE for value in codes))
        with self.assertRaises(v.VersionError):
            v.parse_manifest("versionName=5.8.4\nbaseVersionCode=210000\n")

    def test_malformed_duplicate_missing_and_unknown_properties_fail_closed(self):
        for text in [
            "versionName=5.8.4\n",
            "versionName=5.8.4\nversionCode=584\n",
            "versionName=5.8.4\nbaseVersionCode=584\nbaseVersionCode=585\n",
            "versionName=5.8.4\nbaseVersionCode=584\nother=1\n",
            "versionName=5.8\nbaseVersionCode=584\n",
            "versionName=5.08.4\nbaseVersionCode=584\n",
            "versionName=5.8.4-rc1\nbaseVersionCode=584\n",
            "versionName=5.8.4\nbaseVersionCode=0\n",
            "versionName=5.8.4\nbaseVersionCode=0584\n",
            "versionName=5.8.4\nbaseVersionCode=-1\n",
            "versionName=5.8.4\nbaseVersionCode=584.0\n",
            "versionName=5.8.4\nbaseVersionCode=99999999999999999999\n",
        ]:
            with self.subTest(text=text), self.assertRaises(v.VersionError):
                v.parse_manifest(text)


class HistoryAndFanOutTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git("init", "-q")
        self.git("config", "user.name", "Fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        self.git("config", "core.autocrlf", "false")
        (self.root / "app").mkdir()
        (self.root / "app" / "build.gradle").write_text(
            'def baseVersionCode = 584\n def baseVersionName = "5.8.4"\n',
            encoding="ascii",
        )
        self.base = self.commit()
        (self.root / v.MANIFEST).write_text(
            "versionName=5.8.4\nbaseVersionCode=584\n", encoding="ascii",
        )
        self.source = self.commit()
        self.env = {
            "CI_SERVER_HOST": "git.tak.gov",
            "CI_PROJECT_ID": "6739",
            "CI_PROJECT_PATH": v.HOST_PATH,
            "CI_COMMIT_BRANCH": "main",
            "CI_COMMIT_REF_PROTECTED": "true",
            "CI_PIPELINE_SOURCE": "push",
            "CI_COMMIT_SHA": self.source,
            "CI_COMMIT_BEFORE_SHA": self.base,
            "CI_JOB_TOKEN": "fixture-job-token",
            "WEARTAK_VERSION_ATTESTATION": "fixture-short-lived-id-token",
        }

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.root, text=True).strip()

    def commit(self):
        self.git("add", ".")
        self.git("commit", "-qm", "fixture")
        return self.git("rev-parse", "HEAD")

    def test_initial_migration_uses_legacy_baseline_but_not_for_invalid_manifest(self):
        self.assertEqual(("5.8.4", 584), v.validate(self.root, self.base))
        (self.root / v.MANIFEST).write_text("versionName=5.8.5\nbaseVersionCode=584\n")
        with self.assertRaises(v.VersionError):
            v.validate(self.root, self.base)
        (self.root / v.MANIFEST).write_text("malformed")
        invalid_sha = self.commit()
        with self.assertRaises(v.VersionError):
            v.version_at(self.root, invalid_sha)

    def test_ci_range_supports_mrs_and_rejects_missing_or_mismatched_history(self):
        mr = self.env | {
            "CI_PIPELINE_SOURCE": "merge_request_event",
            "CI_MERGE_REQUEST_DIFF_BASE_SHA": self.base,
        }
        self.assertEqual(self.base, v.ci_base(self.root, mr))
        manual_main = self.env | {"CI_PIPELINE_SOURCE": "web", "CI_COMMIT_BEFORE_SHA": "0" * 40}
        self.assertEqual(self.base, v.ci_base(self.root, manual_main))
        for changes in [
            {"CI_COMMIT_SHA": "e" * 40},
            {"CI_COMMIT_BEFORE_SHA": "0" * 40},
            {"CI_COMMIT_BEFORE_SHA": "f" * 40},
        ]:
            with self.subTest(changes=changes), self.assertRaises(v.VersionError):
                v.ci_base(self.root, self.env | changes)

    def test_nonmanifest_push_never_triggers(self):
        (self.root / "code.txt").write_text("not a manifest change")
        source = self.commit()
        env = self.env | {"CI_COMMIT_SHA": source, "CI_COMMIT_BEFORE_SHA": self.source}
        with patch.object(v.urllib.request, "build_opener") as opener:
            with self.assertRaisesRegex(v.VersionError, "No version manifest change"):
                v.fan_out(self.root, env, "7025")
            opener.assert_not_called()

    def test_all_approved_targets_receive_pinned_contract_and_only_job_token_auth(self):
        # Explicit expected destinations catch accidental ref/ID drift, not just map iteration.
        targets = {
            "7025": "main", "6988": "main", "6952": "main", "6948": "main",
            "6942": "main", "6941": "main", "6933": "main", "6912": "main",
            "6910": "main", "6890": "master",
            "6786": "aegorsuch-weartak-313-ditto-plugin-extraction", "6737": "main",
        }
        output = io.StringIO()
        with patch.object(v.urllib.request, "build_opener") as build_opener, contextlib.redirect_stdout(output):
            opener = build_opener.return_value
            for target, ref in targets.items():
                opener.open.return_value.__enter__.return_value = io.StringIO(json.dumps({
                    "id": 123, "project_id": int(target), "ref": ref, "source": "pipeline",
                }))
                v.fan_out(self.root, self.env, target)
                request = opener.open.call_args.args[0]
                self.assertEqual(f"{v.API}/projects/{target}/trigger/pipeline", request.full_url)
                self.assertEqual("POST", request.method)
                fields = urllib.parse.parse_qs(request.data.decode("ascii"))
                self.assertEqual({
                    "token": ["fixture-job-token"], "ref": [ref],
                    "variables[WEARTAK_VERSION_NAME]": ["5.8.4"],
                    "variables[WEARTAK_BASE_VERSION_CODE]": ["584"],
                    "variables[WEARTAK_VERSION_SOURCE_PROJECT_ID]": ["6739"],
                    "variables[WEARTAK_VERSION_SOURCE_SHA]": [self.source],
                    "variables[WEARTAK_VERSION_ATTESTATION]": ["fixture-short-lived-id-token"],
                }, fields)
                self.assertNotIn("token", request.full_url)
                self.assertIsNone(request.get_header("Authorization"))
        self.assertNotIn("fixture-job-token", output.getvalue())
        self.assertNotIn("fixture-short-lived-id-token", output.getvalue())

    def test_unprotected_nonmain_fork_nonpush_tag_unknown_target_or_missing_tokens_do_not_trigger(self):
        for changes in [
            {"CI_SERVER_HOST": "example.invalid"}, {"CI_PROJECT_ID": "999"},
            {"CI_PROJECT_PATH": "other/project"}, {"CI_COMMIT_BRANCH": "feature"},
            {"CI_COMMIT_REF_PROTECTED": "false"}, {"CI_PIPELINE_SOURCE": "web"},
            {"CI_PIPELINE_SOURCE": "pipeline"}, {"CI_COMMIT_TAG": "v5.8.4"},
            {"CI_JOB_TOKEN": ""}, {"WEARTAK_VERSION_ATTESTATION": ""},
        ]:
            with self.subTest(changes=changes), patch.object(v.urllib.request, "build_opener") as opener:
                with self.assertRaises(v.VersionError):
                    v.fan_out(self.root, self.env | changes, "7025")
                opener.assert_not_called()
        with self.assertRaises(v.VersionError):
            v.fan_out(self.root, self.env, "999")

    def test_trigger_errors_do_not_log_credentials_or_retry(self):
        error = urllib.error.HTTPError(
            "https://git.tak.gov", 403, "forbidden", {},
            io.BytesIO(b"fixture-job-token fixture-short-lived-id-token"),
        )
        with patch.object(v.urllib.request, "build_opener") as build_opener:
            opener = build_opener.return_value
            opener.open.side_effect = error
            with self.assertRaisesRegex(v.VersionError, "HTTP 403") as caught:
                v.fan_out(self.root, self.env, "7025")
            self.assertNotIn("fixture-job-token", str(caught.exception))
            self.assertNotIn("fixture-short-lived-id-token", str(caught.exception))
            self.assertEqual(1, opener.open.call_count)
        with self.assertRaisesRegex(v.VersionError, "redirect"):
            v.NoRedirect().redirect_request(None, None, 302, "", {}, "https://other.invalid")
        for result in [
            None,
            {"id": 123, "project_id": 7025, "ref": "main", "source": "trigger"},
            {"id": 123, "project_id": 7025, "ref": "other", "source": "pipeline"},
        ]:
            with self.subTest(result=result), patch.object(v.urllib.request, "build_opener") as build_opener:
                build_opener.return_value.open.return_value.__enter__.return_value = io.StringIO(
                    json.dumps(result)
                )
                with self.assertRaisesRegex(v.VersionError, "unexpected provenance"):
                    v.fan_out(self.root, self.env, "7025")

    def test_fan_out_ci_scope_and_oidc_are_not_available_to_branch_or_tag_pipelines(self):
        ci = (ROOT / ".gitlab-ci.yml").read_text(encoding="utf-8")
        job = ci.split("\nwear_version_fan_out:", 1)[1].split("\nhost_lint_debug:", 1)[0]
        self.assertIn("stage: sync", job)
        self.assertIn("id_tokens:", job)
        self.assertIn(f"aud: {v.ATTESTATION_AUDIENCE}", job)
        self.assertIn('CI_PROJECT_ID == "6739"', job)
        self.assertIn('CI_COMMIT_BRANCH == "main"', job)
        self.assertIn('CI_COMMIT_REF_PROTECTED == "true"', job)
        self.assertIn('CI_PIPELINE_SOURCE == "push"', job)
        self.assertIn("changes:\n        - wear-version.properties", job)
        self.assertEqual(1, job.count("- if:"))
        self.assertNotIn("when: always", job)
        self.assertNotIn("allow_failure", job)
        self.assertNotIn("retry:", job)


if __name__ == "__main__":
    unittest.main()
