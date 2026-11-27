import unittest

from ci import build_plugin_sdk as builder


class WindowsCandidateChecksumContextTests(unittest.TestCase):
    def setUp(self):
        self.environment = {
            "CI": "true",
            "CI_SERVER_HOST": "git.tak.gov",
            "CI_PROJECT_ID": "6739",
            "CI_PROJECT_PATH": "core/weartak-core/wearos-tak-civ",
            "CI_COMMIT_BRANCH": "main",
            "CI_COMMIT_REF_PROTECTED": "true",
            "CI_PIPELINE_SOURCE": "push",
            "CI_JOB_ID": "8049000",
            "CI_PIPELINE_ID": "1892700",
        }

    def test_only_protected_core_main_pipeline_is_allowed(self):
        builder.validate_candidate_context("windows", self.environment)

    def test_candidate_build_is_forced_uncached_but_normal_build_is_unchanged(self):
        candidate = builder.build_arguments("gradlew.bat", candidate=True)
        normal = builder.build_arguments("gradlew.bat")
        for flag in ("--rerun-tasks", "--no-build-cache", "--no-configuration-cache"):
            self.assertIn(flag, candidate)
            self.assertNotIn(flag, normal)

    def test_rejects_non_windows_execution(self):
        with self.assertRaises(RuntimeError):
            builder.validate_candidate_context("linux", self.environment)

    def test_rejects_unprotected_or_untrusted_pipeline_context(self):
        for key, value in [
            ("CI_COMMIT_REF_PROTECTED", "false"),
            ("CI_COMMIT_BRANCH", "feature"),
            ("CI_PROJECT_ID", "other"),
            ("CI_PIPELINE_SOURCE", "merge_request_event"),
            ("CI_SERVER_HOST", "example.invalid"),
        ]:
            with self.subTest(key=key, value=value):
                environment = dict(self.environment)
                environment[key] = value
                with self.assertRaises(RuntimeError):
                    builder.validate_candidate_context("windows", environment)

    def test_requires_ci_job_and_pipeline_provenance(self):
        for key in ("CI_JOB_ID", "CI_PIPELINE_ID"):
            with self.subTest(key=key):
                environment = dict(self.environment)
                environment.pop(key)
                with self.assertRaises(RuntimeError):
                    builder.validate_candidate_context("windows", environment)
