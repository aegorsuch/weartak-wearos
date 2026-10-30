from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]


class HostLintTests(unittest.TestCase):
    def setUp(self):
        ci = (ROOT / ".gitlab-ci.yml").read_text()
        self.job = ci.split("\nhost_lint_debug:", 1)[1].split(
            "\nagp_resolution_diagnostic:", 1
        )[0]

    def test_full_lint_errors_are_blocking_without_new_unit_or_release_gates(self):
        self.assertIn("stage: test", self.job)
        self.assertIn(":app:lintDebug", self.job)
        self.assertNotIn(":app:testDebugUnitTest", self.job)
        self.assertIn("--max-workers=2 -Pkotlin.compiler.execution.strategy=in-process", self.job)
        self.assertNotIn("allow_failure", self.job)
        self.assertNotIn("assembleRelease", self.job)
        self.assertNotIn("KEYSTORE", self.job)
        build = (ROOT / "app" / "build.gradle").read_text()
        self.assertRegex(build, r"lint\s*\{\s*abortOnError true")
        self.assertNotRegex(build, r"\bbaseline\s*[=(]")
        self.assertNotIn("warningsAsErrors true", build)

    def test_reports_survive_failure_and_existing_pipeline_scope_is_preserved(self):
        self.assertIn("when: always", self.job)
        self.assertIn("app/build/reports/lint-results-debug.*", self.job)
        self.assertNotIn("rules:", self.job)
        self.assertNotIn("only:", self.job)
        self.assertNotIn("except:", self.job)


if __name__ == "__main__":
    unittest.main()
