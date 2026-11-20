from pathlib import Path
import subprocess
import tempfile
import unittest

from ci import wear_version as v


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


class VersionHistoryTests(unittest.TestCase):
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
            "CI_COMMIT_BRANCH": "main",
            "CI_PIPELINE_SOURCE": "push",
            "CI_COMMIT_SHA": self.source,
            "CI_COMMIT_BEFORE_SHA": self.base,
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
